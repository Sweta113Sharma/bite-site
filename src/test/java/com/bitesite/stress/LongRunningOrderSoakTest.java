package com.bitesite.stress;

import com.bitesite.dao.CategoryDao;
import com.bitesite.dao.MenuItemDao;
import com.bitesite.dao.OutletDao;
import com.bitesite.dao.UserDao;
import com.bitesite.dto.GatewayOrder;
import com.bitesite.dto.GatewayRefund;
import com.bitesite.model.Category;
import com.bitesite.model.MenuItem;
import com.bitesite.model.Outlet;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import com.bitesite.service.PaymentGateway;
import com.bitesite.service.UserService;
import com.bitesite.tenant.Tenant;
import com.bitesite.tenant.TenantDao;
import com.bitesite.tenant.TenantStatus;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A long, realistic lunch service across every canteen of every college, driven over real
 * HTTP, and then an audit of every rupee and every order it produced.
 *
 * <p>Opt-in, and slow on purpose:
 * <pre>
 *   mvn -Dstress=true -Dtest=LongRunningOrderSoakTest test
 *   mvn -Dstress=true -Dtest=LongRunningOrderSoakTest -Dstress.soak.minutes=60 \
 *       -Dstress.soak.ordersPerOutlet=400 test
 * </pre>
 * Defaults: 3 colleges (production's count) × 3 canteens × 130 orders = 1,170 orders over
 * 15 minutes, shaped like a lunch rush, every canteen required to complete at least 100.
 * The report is printed and written to {@code target/stress-reports/}.
 *
 * <h2>Why this exists next to {@link ConcurrentMultiCollegeOrderStressTest}</h2>
 * That test fires a burst at the service layer and is over in seconds. It cannot see the
 * things that go wrong over a service: sessions stored in MySQL on every request, a
 * connection pool that drifts toward exhaustion, latency that creeps as tables grow, heap
 * that does not come back. It also never runs a payment callback against its own
 * webhook, never cancels anything, and never hands anything over at a counter. This does
 * all of that, through the real embedded Tomcat, the real security filter chain, CSRF and
 * all, with the only substitute being the payment gateway.
 *
 * <h2>What a run does</h2>
 * <ul>
 *   <li><b>Students</b> (160 per college, each with their own session and IP) open the
 *       menu, add one to three items the way the menu page does (picking another dish
 *       when one has sold out), sometimes apply the college's promo code, check out, and
 *       pay. Payment confirmation arrives the way
 *       Razorpay actually delivers it: client callback and webhook in either order, both
 *       at once, one of them lost, or the webhook retried. Some abandon at the till,
 *       some cancel inside their window. Everyone else watches the order page poll until
 *       it is ready, then shows their pickup code at the counter.
 *   <li><b>Staff</b> (two per canteen, logged in on the outlet portal) poll the kitchen
 *       queue as {@code queue-poll.js} does, start orders, mark them ready, occasionally
 *       cancel one or take an item off it, and hand orders over against the student's
 *       code. Two people work each queue, so they collide on the same order, as they do.
 *   <li><b>An admin</b> keeps the platform dashboard and analytics open.
 * </ul>
 *
 * <h2>What it fails on</h2>
 * Correctness only, never speed, because speed on this machine says little about the
 * Burstable production database. It fails on: any 5xx, any transport error, any 403
 * (CSRF or security breaking under load), any journey that never finishes, any canteen
 * completing fewer than {@code minCompletedPerOutlet} orders, any order left mid-flight,
 * any paid order without exactly one captured payment, any refund that does not match
 * what was cancelled, any money on an abandoned order, a duplicate token or live pickup
 * code, an order or line crossing a college or canteen boundary, a promo used beyond its
 * cap, or a total that does not add up. Latency, pool pressure, heap and drift over time
 * are reported, not asserted.
 *
 * <p><b>Local only.</b> Production is one Burstable core serving live colleges. The same
 * reasoning as the burst test applies: every failure this looks for reproduces locally,
 * and more sharply.
 *
 * <p>The only daily-cap behaviour reported rather than asserted is overshoot: the cap is
 * best-effort by design ({@code OrderService}, before {@code requireDailyCapacity}), so a
 * small overshoot under concurrency is the documented trade-off, not a bug.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Every simulated student gets their own client IP through X-Forwarded-For, as
        // they would behind Azure's front end. Without it, the per-IP login limit (10 per
        // five minutes) locks out the whole run after the tenth student.
        "server.forward-headers-strategy=framework",
        // The test profile shrinks the pool to 4 so the normal suite's many contexts fit
        // in one MySQL. A load test has to run at production's default, or it measures
        // the test profile.
        "spring.datasource.hikari.maximum-pool-size=10",
        "spring.datasource.hikari.minimum-idle=2"
})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "stress", matches = "true")
class LongRunningOrderSoakTest {

    // ── Dials ─────────────────────────────────────────────────────────────────

    private static final int COLLEGES = Integer.getInteger("stress.soak.colleges", 3);
    private static final int OUTLETS_PER_COLLEGE = Integer.getInteger("stress.soak.outlets", 3);
    /** Arrivals per canteen. Above the completion floor, because some are meant to fail. */
    private static final int ORDERS_PER_OUTLET = Integer.getInteger("stress.soak.ordersPerOutlet", 130);
    private static final int MIN_COMPLETED_PER_OUTLET = Integer.getInteger("stress.soak.minCompletedPerOutlet", 100);
    private static final int STUDENTS_PER_COLLEGE = Integer.getInteger("stress.soak.students", 160);
    private static final int STAFF_PER_OUTLET = Integer.getInteger("stress.soak.staffPerOutlet", 2);
    private static final int MINUTES = Integer.getInteger("stress.soak.minutes", 15);
    private static final int REPORT_SECONDS = Integer.getInteger("stress.soak.reportSeconds", 30);
    /** How long after the last arrival everything in flight gets to finish. */
    private static final int DRAIN_MINUTES = Integer.getInteger("stress.soak.drainMinutes", 10);
    private static final long SEED = Long.getLong("stress.soak.seed", System.nanoTime());

    // How the day goes. Each is a share of arrivals.
    private static final double P_ABANDON = 0.04;        // opens the till, never pays
    private static final double P_SELF_CANCEL = 0.05;    // pays, then cancels inside the window
    private static final double P_STAFF_CANCEL = 0.02;   // kitchen cancels the whole order
    private static final double P_ITEM_CANCEL = 0.05;    // kitchen takes one line off
    private static final double P_PROMO = 0.25;          // tries the college promo
    private static final double P_WEBHOOK_RETRY = 0.30;  // Razorpay sends the webhook twice

    private static final int PROMO_CAP = 40;
    /** Low enough that the busiest item on each menu sells out mid-run. */
    private static final int DAILY_LIMIT = 30;
    private static final Duration JOURNEY_TIMEOUT = Duration.ofMinutes(12);
    private static final String PASSWORD = "Soak@12345";

    // ── Stub gateway ──────────────────────────────────────────────────────────

    /**
     * Razorpay is the only thing replaced. Signatures always verify, orders get globally
     * unique ids (payments has unique keys on both gateway ids, and rows survive between
     * runs), and refunds are remembered so the reconciliation job reads back what was
     * issued, as it would from Razorpay.
     */
    @TestConfiguration
    static class StubGateway {
        @Bean
        @Primary
        PaymentGateway soakPaymentGateway() {
            return new PaymentGateway() {
                private final AtomicInteger seq = new AtomicInteger();
                private final String prefix = "soak_" + UUID.randomUUID().toString().substring(0, 8) + "_";
                private final Map<String, List<GatewayRefund>> refunds = new ConcurrentHashMap<>();

                @Override
                public GatewayOrder createOrder(BigDecimal amountRupees, String receipt) {
                    return new GatewayOrder(prefix + "order_" + seq.incrementAndGet(), "stub_key",
                            amountRupees.multiply(BigDecimal.valueOf(100)).longValue(), "INR");
                }

                @Override
                public boolean verifyPaymentSignature(String o, String p, String s) {
                    return true;
                }

                @Override
                public boolean verifyWebhookSignature(String payload, String header) {
                    return true;
                }

                @Override
                public void refund(String gatewayPaymentId, BigDecimal amountRupees) {
                    refundPart(gatewayPaymentId, amountRupees);
                }

                @Override
                public GatewayRefund refundPart(String gatewayPaymentId, BigDecimal amountRupees) {
                    GatewayRefund refund = new GatewayRefund(prefix + "rfnd_" + seq.incrementAndGet(),
                            amountRupees, "processed");
                    refunds.computeIfAbsent(gatewayPaymentId, k -> new CopyOnWriteArrayList<>()).add(refund);
                    return refund;
                }

                @Override
                public List<GatewayRefund> refundsFor(String gatewayPaymentId) {
                    return List.copyOf(refunds.getOrDefault(gatewayPaymentId, List.of()));
                }
            };
        }
    }

    @Autowired private TenantDao tenantDao;
    @Autowired private OutletDao outletDao;
    @Autowired private CategoryDao categoryDao;
    @Autowired private MenuItemDao menuItemDao;
    @Autowired private UserDao userDao;
    @Autowired private UserService userService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PaymentGateway paymentGateway;

    @LocalServerPort private int port;
    @org.springframework.beans.factory.annotation.Value("${spring.datasource.url}") private String dbUrl;
    @org.springframework.beans.factory.annotation.Value("${spring.datasource.username}") private String dbUser;
    @org.springframework.beans.factory.annotation.Value("${spring.datasource.password:}") private String dbPassword;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private record Account(long userId, String email) {}

    private record OutletFixture(long tenantId, long outletId, String name, List<Long> menuItemIds,
                                 long limitedItemId, List<Account> staff) {}

    private record CollegeFixture(long tenantId, String name, List<OutletFixture> outlets,
                                  List<Account> students, String promoCode, long promoId) {}

    private final List<CollegeFixture> colleges = new ArrayList<>();
    private Account admin;
    private String runId;

    @BeforeAll
    void seedColleges() {
        runId = UUID.randomUUID().toString().substring(0, 6);
        // One BCrypt for every student: they share a password, and hashing 480 of them
        // one by one would spend a minute before the test had started.
        String studentHash = passwordEncoder.encode(PASSWORD);

        for (int c = 0; c < COLLEGES; c++) {
            String collegeName = "Soak College " + runId + "-" + (char) ('A' + c);
            Tenant tenant = tenantDao.save(Tenant.builder().name(collegeName).status(TenantStatus.ACTIVE).build());
            long tenantId = tenant.getId();

            List<OutletFixture> outlets = new ArrayList<>();
            for (int o = 0; o < OUTLETS_PER_COLLEGE; o++) {
                Outlet outlet = outletDao.save(Outlet.builder().tenantId(tenantId)
                        .name("Canteen " + (char) ('A' + c) + (o + 1)).active(true).acceptingOrders(true).build());
                long outletId = outlet.getId();
                long snacks = categoryDao.save(Category.builder().tenantId(tenantId).outletId(outletId)
                        .name("Snacks").sortOrder(0).build()).getId();
                long meals = categoryDao.save(Category.builder().tenantId(tenantId).outletId(outletId)
                        .name("Meals").sortOrder(1).build()).getId();

                // Eight dishes at canteen prices. The first carries a daily cap and is the
                // one students reach for most, so it sells out partway through.
                String[] names = {"Masala Dosa", "Samosa", "Veg Puff", "Cold Coffee",
                        "Veg Thali", "Paneer Roll", "Chole Bhature", "Maggi"};
                int[] prices = {60, 20, 25, 45, 110, 70, 90, 40};
                List<Long> items = new ArrayList<>();
                for (int m = 0; m < names.length; m++) {
                    items.add(menuItemDao.save(MenuItem.builder().tenantId(tenantId).outletId(outletId)
                            .name(names[m]).categoryId(m < 4 ? snacks : meals)
                            .price(new BigDecimal(prices[m] + ".00")).available(true)
                            .dailyLimit(m == 0 ? DAILY_LIMIT : null).build()).getId());
                }

                List<Account> staff = new ArrayList<>();
                for (int s = 0; s < STAFF_PER_OUTLET; s++) {
                    String email = "soak-" + runId + "-staff-" + c + "-" + o + "-" + s + "@test.local";
                    // The first is the manager (reports, order history); the rest operate.
                    Role role = s == 0 ? Role.CANTEEN_MANAGER : Role.CANTEEN_OPERATOR;
                    User user = userService.createUser(tenantId, outletId, "Staff " + c + o + s, email, PASSWORD, role);
                    staff.add(new Account(user.getId(), email));
                }
                outlets.add(new OutletFixture(tenantId, outletId, outlet.getName(), items, items.get(0), staff));
            }

            List<Account> students = new ArrayList<>();
            for (int s = 0; s < STUDENTS_PER_COLLEGE; s++) {
                String email = "soak-" + runId + "-" + c + "-" + s + "@test.local";
                User user = userDao.save(User.builder().tenantId(tenantId).name("Student " + c + "-" + s)
                        .email(email).passwordHash(studentHash).role(Role.USER).activeRole(Role.USER)
                        .active(true).emailVerified(true).phoneVerified(true).build());
                students.add(new Account(user.getId(), email));
            }

            // A college-wide promo with a small cap: redemption is a locked check-then-act,
            // and a lunch rush of students racing for the last few uses is its real test.
            String promoCode = ("SOAK" + runId + (char) ('A' + c)).toUpperCase();
            jdbc.update("INSERT INTO promo_codes (code, description, discount_type, discount_value, max_discount, "
                            + "min_order_value, funded_by, tenant_id, outlet_id, valid_from, valid_until, "
                            + "max_redemptions, max_per_user, is_active) "
                            + "VALUES (?, 'Soak test', 'PERCENT', 10.00, 30.00, NULL, 'PLATFORM', ?, NULL, NULL, NULL, ?, 1, 1)",
                    promoCode, tenantId, PROMO_CAP);
            long promoId = jdbc.queryForObject("SELECT id FROM promo_codes WHERE code = ?", Long.class, promoCode);

            colleges.add(new CollegeFixture(tenantId, collegeName, outlets, students, promoCode, promoId));
        }

        String adminEmail = "soak-" + runId + "-admin@test.local";
        User adminUser = userService.createUser(null, null, "Soak Admin", adminEmail, PASSWORD, Role.SUPER_ADMIN);
        admin = new Account(adminUser.getId(), adminEmail);
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    /** One client for everyone; cookies are carried per session by hand, so 500 sessions
     *  do not mean 500 clients each with its own selector thread. */
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private static final Pattern CSRF_META = Pattern.compile("name=\"_csrf\" content=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
    private static final Pattern GATEWAY_ORDER = Pattern.compile("gatewayOrderId\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern PICKUP_CODE = Pattern.compile("pickup-code-panel__code\"[^>]*>\\s*([0-9A-Za-z]+)\\s*<");
    private static final Pattern CHECKOUT_REDIRECT = Pattern.compile("/student/checkout/(\\d+)");

    private record Resp(int status, String body, String location) {
        boolean redirect() {
            return status == 302 || status == 303;
        }
    }

    /** A browser: its own cookies, its own CSRF token, its own client IP. */
    private final class Session {
        final String host;
        final String clientIp;
        final String who;
        final Map<String, String> cookies = new ConcurrentHashMap<>();
        volatile String csrf;

        Session(String host, String clientIp, String who) {
            this.host = host;
            this.clientIp = clientIp;
            this.who = who;
        }

        Resp get(String label, String path) {
            return send(label, HttpRequest.newBuilder(uri(path)).GET());
        }

        Resp getJson(String label, String path) {
            return send(label, HttpRequest.newBuilder(uri(path)).GET().header("Accept", "application/json"));
        }

        Resp post(String label, String path, Map<String, String> form) {
            return post(label, path, form, null);
        }

        Resp post(String label, String path, Map<String, String> form, String accept) {
            Map<String, String> body = new LinkedHashMap<>(form);
            if (csrf != null) {
                body.put("_csrf", csrf);
            }
            HttpRequest.Builder b = HttpRequest.newBuilder(uri(path))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(encode(body)));
            if (accept != null) {
                b.header("Accept", accept);
            }
            return send(label, b);
        }

        URI uri(String path) {
            return URI.create("http://" + host + ":" + port + path);
        }

        Resp send(String label, HttpRequest.Builder b) {
            b.timeout(Duration.ofSeconds(60))
                    .header("X-Forwarded-For", clientIp)
                    .header("Accept-Encoding", "gzip")
                    .header("User-Agent", "BiteSite-Soak/1.0");
            if (!cookies.isEmpty()) {
                b.header("Cookie", cookies.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; ")));
            }
            long started = System.nanoTime();
            HttpResponse<byte[]> r;
            try {
                r = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                metrics.record(label, -1, (System.nanoTime() - started) / 1000);
                fail("transport " + label + " (" + who + "): " + e);
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
            long micros = (System.nanoTime() - started) / 1000;
            metrics.record(label, r.statusCode(), micros);

            for (String setCookie : r.headers().allValues("set-cookie")) {
                String pair = setCookie.split(";", 2)[0];
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String name = pair.substring(0, eq).trim();
                    String value = pair.substring(eq + 1).trim();
                    if (value.isEmpty() || setCookie.toLowerCase().contains("max-age=0")) {
                        cookies.remove(name);
                    } else {
                        cookies.put(name, value);
                    }
                }
            }
            String body = decode(r);
            Matcher m = CSRF_META.matcher(body);
            if (m.find()) {
                csrf = m.group(1);
            } else {
                Matcher in = CSRF_INPUT.matcher(body);
                if (in.find()) {
                    csrf = in.group(1);
                }
            }
            String location = r.headers().firstValue("location").orElse(null);
            if (r.statusCode() >= 500) {
                fail("HTTP " + r.statusCode() + " " + label + " (" + who + "): " + snippet(body));
            } else if (r.statusCode() == 403) {
                fail("HTTP 403 " + label + " (" + who + "): security/CSRF refused a legitimate request");
            }
            return new Resp(r.statusCode(), body, location);
        }
    }

    private static String decode(HttpResponse<byte[]> r) {
        byte[] bytes = r.body();
        if (r.headers().firstValue("content-encoding").orElse("").contains("gzip")) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                bytes = in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String encode(Map<String, String> form) {
        StringJoiner j = new StringJoiner("&");
        form.forEach((k, v) -> j.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return j.toString();
    }

    private static String snippet(String body) {
        String flat = body.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }

    private Session login(String host, String email, String ip) {
        Session s = new Session(host, ip, email);
        s.get("GET /login", "/login");
        Resp r = s.post("POST /login", "/login", Map.of("username", email, "password", PASSWORD));
        if (!r.redirect() || r.location() == null || r.location().contains("error")) {
            throw new IllegalStateException("login failed for " + email + " on " + host + ": "
                    + r.status() + " -> " + r.location());
        }
        // Follow the landing redirect once: it is what a browser does, and it renders a
        // page that carries the post-login CSRF token (the token rotates at sign-in).
        String landing = URI.create(r.location()).getPath();
        s.get("GET (post-login landing)", landing);
        return s;
    }

    // ── Metrics ───────────────────────────────────────────────────────────────

    private static final class Endpoint {
        final LongAdder count = new LongAdder();
        final Map<Integer, LongAdder> statuses = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<Integer> millis = new ConcurrentLinkedQueue<>();
    }

    private record Interval(long atSecond, long requests, int p50, int p95, int max, int poolActive,
                            int poolWaiting, long heapMb, long sessions, long ordersCreated, long completed) {}

    private final class Metrics {
        final Map<String, Endpoint> endpoints = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<Integer> window = new ConcurrentLinkedQueue<>();
        final List<Interval> intervals = new CopyOnWriteArrayList<>();
        final AtomicInteger maxPoolActive = new AtomicInteger();
        final AtomicInteger maxPoolWaiting = new AtomicInteger();
        final AtomicLong maxHeapMb = new AtomicLong();
        final LongAdder poolSamples = new LongAdder();
        final LongAdder poolSaturatedSamples = new LongAdder();

        void record(String label, int status, long micros) {
            Endpoint e = endpoints.computeIfAbsent(label, k -> new Endpoint());
            int ms = (int) Math.min(Integer.MAX_VALUE, micros / 1000);
            e.count.increment();
            e.statuses.computeIfAbsent(status, k -> new LongAdder()).increment();
            e.millis.add(ms);
            window.add(ms);
        }
    }

    private final Metrics metrics = new Metrics();
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();

    private void count(String name) {
        counters.computeIfAbsent(name, k -> new LongAdder()).increment();
    }

    private long counter(String name) {
        LongAdder a = counters.get(name);
        return a == null ? 0 : a.sum();
    }

    private void fail(String message) {
        failures.add(message);
    }

    private static int percentile(int[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    private static int[] sorted(Iterable<Integer> values) {
        List<Integer> copy = new ArrayList<>();
        values.forEach(copy::add);
        int[] a = copy.stream().mapToInt(Integer::intValue).toArray();
        Arrays.sort(a);
        return a;
    }

    // ── The counter: where a student's code meets the staff who hand the order over ──

    /** Per canteen: order id → the code the student is holding up. */
    private final Map<Long, Map<Long, String>> pickupCounters = new ConcurrentHashMap<>();

    private Map<Long, String> counterAt(long outletId) {
        return pickupCounters.computeIfAbsent(outletId, k -> new ConcurrentHashMap<>());
    }

    private enum KitchenPlan { NORMAL, CANCEL, ITEM_CANCEL }

    /** What the kitchen will do with each order, decided once so both staff agree. */
    private final Map<Long, KitchenPlan> plans = new ConcurrentHashMap<>();
    private final Map<Long, Long> preparingSince = new ConcurrentHashMap<>();
    private final Map<Long, Long> cookMillis = new ConcurrentHashMap<>();
    private final Set<String> claimed = ConcurrentHashMap.newKeySet();

    private KitchenPlan planFor(long orderId) {
        return plans.computeIfAbsent(orderId, id -> {
            double r = ThreadLocalRandom.current().nextDouble();
            cookMillis.put(id, ThreadLocalRandom.current().nextLong(3_000, 15_000));
            if (r < P_STAFF_CANCEL) {
                return KitchenPlan.CANCEL;
            }
            return r < P_STAFF_CANCEL + P_ITEM_CANCEL ? KitchenPlan.ITEM_CANCEL : KitchenPlan.NORMAL;
        });
    }

    // ── The run ───────────────────────────────────────────────────────────────

    private record Arrival(long atMillis, CollegeFixture college, OutletFixture outlet) {}

    private final AtomicBoolean staffStop = new AtomicBoolean();
    private final AtomicInteger journeysInFlight = new AtomicInteger();
    private volatile long runStartedNanos;

    @Test
    void aWholeLunchServiceAcrossEveryCanteenHoldsUp() throws Exception {
        long loadMillis = MINUTES * 60_000L;
        // unwrap, not a cast: the bean may be proxied (Sentry, transaction awareness).
        HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        System.out.printf("%n[soak] run %s seed %d: %d colleges × %d canteens × %d orders over %d min "
                        + "(students %d/college, staff %d/canteen, port %d)%n",
                runId, SEED, COLLEGES, OUTLETS_PER_COLLEGE, ORDERS_PER_OUTLET, MINUTES,
                STUDENTS_PER_COLLEGE, STAFF_PER_OUTLET, port);

        // ---- sign everyone in, as separate browsers on separate IPs ----
        Map<Long, BlockingQueue<Session>> idleStudents = new ConcurrentHashMap<>();
        List<Runnable> staffLoops = new ArrayList<>();
        try (ExecutorService logins = Executors.newVirtualThreadPerTaskExecutor()) {
            // BCrypt runs on the server; more than a handful at once only queues on its CPU.
            Semaphore gate = new Semaphore(16);
            List<Future<?>> pending = new ArrayList<>();
            for (int c = 0; c < colleges.size(); c++) {
                CollegeFixture college = colleges.get(c);
                BlockingQueue<Session> idle = new LinkedBlockingQueue<>();
                idleStudents.put(college.tenantId(), idle);
                for (int s = 0; s < college.students().size(); s++) {
                    Account a = college.students().get(s);
                    String ip = "10." + (c + 1) + "." + (s / 250) + "." + (s % 250 + 1);
                    pending.add(logins.submit(() -> {
                        gate.acquireUninterruptibly();
                        try {
                            idle.add(login("localhost", a.email(), ip));
                        } finally {
                            gate.release();
                        }
                    }));
                }
                for (int o = 0; o < college.outlets().size(); o++) {
                    OutletFixture outlet = college.outlets().get(o);
                    for (int s = 0; s < outlet.staff().size(); s++) {
                        Account a = outlet.staff().get(s);
                        String ip = "172.16." + (c * 10 + o) + "." + (s + 1);
                        boolean manager = s == 0;
                        Session session = login("outlet.localhost", a.email(), ip);
                        staffLoops.add(() -> staffLoop(outlet, session, manager));
                    }
                }
            }
            for (Future<?> f : pending) {
                f.get();
            }
        }
        Session adminSession = login("admin.localhost", admin.email(), "192.168.0.10");
        System.out.printf("[soak] %d students and %d staff signed in%n",
                colleges.size() * STUDENTS_PER_COLLEGE, staffLoops.size());

        // ---- the day's arrivals: a steady trickle with a lunch rush in the middle ----
        Random rnd = new Random(SEED);
        List<Arrival> arrivals = new ArrayList<>();
        for (CollegeFixture college : colleges) {
            for (OutletFixture outlet : college.outlets()) {
                for (int i = 0; i < ORDERS_PER_OUTLET; i++) {
                    double t = rnd.nextBoolean()
                            ? rnd.nextDouble()
                            : Math.max(0, Math.min(1, 0.5 + rnd.nextGaussian() * 0.12));
                    arrivals.add(new Arrival((long) (t * loadMillis), college, outlet));
                }
            }
        }
        arrivals.sort(Comparator.comparingLong(Arrival::atMillis));

        runStartedNanos = System.nanoTime();
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
        monitor.scheduleAtFixedRate(() -> tick(pool), REPORT_SECONDS, REPORT_SECONDS, TimeUnit.SECONDS);
        // Queries take milliseconds, so a pool read once per report interval almost never
        // catches one in flight. Five times a second does, and says how often anything had
        // to wait for a connection at all.
        ScheduledExecutorService poolSampler = Executors.newSingleThreadScheduledExecutor();
        poolSampler.scheduleAtFixedRate(() -> {
            int active = pool.getActiveConnections();
            int waiting = pool.getThreadsAwaitingConnection();
            metrics.maxPoolActive.accumulateAndGet(active, Math::max);
            metrics.maxPoolWaiting.accumulateAndGet(waiting, Math::max);
            metrics.poolSamples.increment();
            if (waiting > 0) {
                metrics.poolSaturatedSamples.increment();
            }
            if (waiting >= 5) {
                captureStall(active, waiting);
            }
        }, 200, 200, TimeUnit.MILLISECONDS);

        ExecutorService staff = Executors.newVirtualThreadPerTaskExecutor();
        staffLoops.forEach(staff::submit);
        AtomicBoolean adminStop = new AtomicBoolean();
        ExecutorService adminPool = Executors.newVirtualThreadPerTaskExecutor();
        adminPool.submit(() -> adminLoop(adminSession, adminStop));

        ExecutorService journeys = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> launched = new ArrayList<>();
        for (Arrival arrival : arrivals) {
            long wait = arrival.atMillis() - elapsedMillis();
            if (wait > 0) {
                Thread.sleep(wait);
            }
            BlockingQueue<Session> idle = idleStudents.get(arrival.college().tenantId());
            launched.add(journeys.submit(() -> {
                journeysInFlight.incrementAndGet();
                Session student = null;
                try {
                    // One journey per student at a time: a real student does not check out
                    // two carts from one session, and the cart is session state.
                    student = idle.poll(60, TimeUnit.SECONDS);
                    if (student == null) {
                        fail("no idle student in " + arrival.college().name() + " for 60s; raise stress.soak.students");
                        return;
                    }
                    journey(student, arrival.college(), arrival.outlet());
                } catch (Exception e) {
                    fail("journey threw " + e);
                } finally {
                    if (student != null) {
                        idle.add(student);
                    }
                    journeysInFlight.decrementAndGet();
                }
            }));
        }
        System.out.printf("[soak] all %d arrivals launched at %ds; draining%n", arrivals.size(), elapsedMillis() / 1000);

        // ---- drain: every journey finishes, then the kitchens empty ----
        long drainDeadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(DRAIN_MINUTES);
        for (Future<?> f : launched) {
            long left = drainDeadline - System.nanoTime();
            try {
                f.get(Math.max(1, left), TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                fail("journeys still running " + DRAIN_MINUTES + " min after the last arrival");
                break;
            }
        }
        journeys.shutdownNow();
        waitForEmptyKitchens(drainDeadline);
        staffStop.set(true);
        adminStop.set(true);
        staff.shutdown();
        adminPool.shutdown();
        staff.awaitTermination(30, TimeUnit.SECONDS);
        adminPool.awaitTermination(30, TimeUnit.SECONDS);
        monitor.shutdownNow();
        poolSampler.shutdownNow();
        tick(pool);
        long runSeconds = elapsedMillis() / 1000;

        // ---- audit ----
        List<String> invariantFailures = audit();
        String report = report(runSeconds, invariantFailures);
        System.out.println(report);
        Path dir = Path.of("target", "stress-reports");
        Files.createDirectories(dir);
        Path file = dir.resolve("order-soak-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + runId + ".txt");
        Files.writeString(file, report);
        System.out.println("[soak] report written to " + file.toAbsolutePath());

        assertThat(failures).as("request-level failures during the run (first 30 in the report)").isEmpty();
        assertThat(invariantFailures).as("data invariants broken after the run").isEmpty();
    }

    private long elapsedMillis() {
        return (System.nanoTime() - runStartedNanos) / 1_000_000;
    }

    /**
     * Orders cannot be left mid-flight when staff clock off. Stops early once nothing has
     * moved for a minute with no student left waiting: an order ready for someone whose
     * journey already failed will never be collected, and the audit reports it either way.
     */
    private void waitForEmptyKitchens(long deadlineNanos) throws InterruptedException {
        String ids = tenantIds();
        int lastLive = -1;
        long unchangedSince = System.nanoTime();
        while (System.nanoTime() < deadlineNanos) {
            Integer live = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE tenant_id IN (" + ids + ") "
                    + "AND status IN ('PAID','PREPARING','READY_FOR_PICKUP')", Integer.class);
            if (live == null || live == 0) {
                return;
            }
            if (live != lastLive) {
                lastLive = live;
                unchangedSince = System.nanoTime();
            } else if (journeysInFlight.get() == 0 && System.nanoTime() - unchangedSince > TimeUnit.SECONDS.toNanos(60)) {
                return;
            }
            Thread.sleep(2_000);
        }
    }

    // ── A student's order, start to finish ────────────────────────────────────

    private void journey(Session s, CollegeFixture college, OutletFixture outlet) throws InterruptedException {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        count("arrivals");
        s.get("GET /student/menu", "/student/menu?outletId=" + outlet.outletId());

        // One to three dishes, the capped favourite most often, added the way the menu
        // page does it: fetch() asking for JSON, so a sold-out refusal is visible.
        int lines = 1 + r.nextInt(3);
        List<Long> added = new ArrayList<>();
        for (int i = 0; i < lines; i++) {
            long itemId = r.nextDouble() < 0.35 ? outlet.limitedItemId() : uncappedDish(outlet, r);
            if (addToCart(s, outlet, itemId, 1 + r.nextInt(2))) {
                added.add(itemId);
            } else {
                // A student whose first choice has sold out picks something else rather
                // than walking away, so a sold-out dish costs the canteen a line, not a sale.
                count("cart add refused (sold out / cap)");
                long other = uncappedDish(outlet, r);
                if (addToCart(s, outlet, other, 1)) {
                    added.add(other);
                    count("picked another dish after a refusal");
                }
            }
        }
        if (added.isEmpty()) {
            count("left without ordering (everything refused)");
            return;
        }

        boolean promo = r.nextDouble() < P_PROMO;
        if (promo) {
            s.post("POST /student/cart/promo", "/student/cart/promo", Map.of("code", college.promoCode()));
            count("promo attempted");
        }
        s.get("GET /student/cart", "/student/cart");

        Long orderId = checkout(s);
        if (orderId == null && promo) {
            // The cap may have gone while the cart was open. A student takes the promo off
            // and tries again rather than walking away hungry.
            s.post("POST /student/cart/promo/remove", "/student/cart/promo/remove", Map.of());
            count("checkout retried without promo");
            orderId = checkout(s);
        }
        if (orderId == null && added.contains(outlet.limitedItemId()) && added.size() > 1) {
            // The capped dish sold out between the tap and the till: drop it, keep the rest.
            s.post("POST /student/cart/remove", "/student/cart/remove",
                    Map.of("menuItemId", String.valueOf(outlet.limitedItemId())));
            count("checkout retried without the sold-out dish");
            orderId = checkout(s);
        }
        if (orderId == null) {
            Resp cart = s.get("GET /student/cart", "/student/cart");
            count(cart.body().contains("sold out") || cart.body().contains("left of")
                    ? "checkout refused: sold out" : "checkout refused: other");
            for (Long item : added) {
                s.post("POST /student/cart/remove", "/student/cart/remove", Map.of("menuItemId", String.valueOf(item)));
            }
            return;
        }
        count("orders created");

        Resp till = s.get("GET /student/checkout/{id}", "/student/checkout/" + orderId);
        Matcher g = GATEWAY_ORDER.matcher(till.body());
        if (!g.find()) {
            fail("checkout page for order " + orderId + " did not carry a gateway order id");
            return;
        }
        String gatewayOrderId = g.group(1);

        if (r.nextDouble() < P_ABANDON) {
            count("abandoned at the till");
            return;
        }

        pay(s, orderId, gatewayOrderId);
        count("paid");

        if (r.nextDouble() < P_SELF_CANCEL) {
            // Inside the window: staff cannot see the order yet, so this should always win.
            Thread.sleep(r.nextLong(1_000, 8_000));
            Resp c = s.post("POST /student/orders/{id}/cancel", "/student/orders/" + orderId + "/cancel", Map.of());
            if (!c.redirect()) {
                fail("self-cancel answered " + c.status());
            }
            Resp page = s.get("GET /student/orders/{id}", "/student/orders/" + orderId);
            count(page.body().contains("Too late to cancel") ? "self-cancel refused (window closed)" : "self-cancelled");
            return;
        }

        waitForCollection(s, outlet, orderId);
    }

    private static long uncappedDish(OutletFixture outlet, ThreadLocalRandom r) {
        return outlet.menuItemIds().get(1 + r.nextInt(outlet.menuItemIds().size() - 1));
    }

    /** The menu page's add: fetch() asking for JSON, so a refusal is visible. True if added. */
    private boolean addToCart(Session s, OutletFixture outlet, long itemId, int quantity) {
        Resp add = s.post("POST /student/cart/add", "/student/cart/add",
                Map.of("menuItemId", String.valueOf(itemId), "quantity", String.valueOf(quantity),
                        "outletId", String.valueOf(outlet.outletId())), "application/json");
        if (add.status() != 200) {
            fail("cart add answered " + add.status());
            return false;
        }
        return !add.body().contains("\"blocked\":true");
    }

    /** POST /student/checkout; the order id is in the redirect when it worked. */
    private Long checkout(Session s) {
        Resp r = s.post("POST /student/checkout", "/student/checkout", Map.of());
        if (r.redirect() && r.location() != null) {
            Matcher m = CHECKOUT_REDIRECT.matcher(r.location());
            if (m.find()) {
                return Long.parseLong(m.group(1));
            }
            return null;
        }
        fail("checkout answered " + r.status());
        return null;
    }

    /**
     * Every way Razorpay actually tells us a payment went through. Callback and webhook
     * both confirm the same payment; whichever arrives second must be a no-op.
     */
    private void pay(Session s, long orderId, String gatewayOrderId) throws InterruptedException {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String paymentId = "pay_soak_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        Runnable callback = () -> {
            Resp c = s.post("POST /student/checkout/{id}/confirm", "/student/checkout/" + orderId + "/confirm",
                    Map.of("razorpay_order_id", gatewayOrderId, "razorpay_payment_id", paymentId,
                            "razorpay_signature", "soak_signature"));
            if (!c.redirect() || c.location() == null || !c.location().contains("/student/orders/" + orderId)) {
                fail("payment callback for order " + orderId + " answered " + c.status() + " -> " + c.location());
            }
        };
        Runnable webhook = () -> {
            webhook(paymentId, gatewayOrderId);
            if (r.nextDouble() < P_WEBHOOK_RETRY) {
                webhook(paymentId, gatewayOrderId);
                count("webhook delivered twice");
            }
        };

        double path = r.nextDouble();
        if (path < 0.45) {
            count("paid: callback then webhook");
            callback.run();
            webhook.run();
        } else if (path < 0.70) {
            count("paid: webhook then callback");
            webhook.run();
            callback.run();
        } else if (path < 0.85) {
            count("paid: callback and webhook racing");
            Thread a = Thread.ofVirtual().start(callback);
            Thread b = Thread.ofVirtual().start(webhook);
            a.join();
            b.join();
        } else if (path < 0.93) {
            count("paid: callback only (webhook lost)");
            callback.run();
        } else {
            count("paid: webhook only (tab closed)");
            webhook.run();
            s.get("GET /student/orders/{id}", "/student/orders/" + orderId);
        }
    }

    private final Session webhookSender = new Session("localhost", "3.7.0.1", "razorpay-webhook");

    private void webhook(String paymentId, String gatewayOrderId) {
        JSONObject entity = new JSONObject().put("id", paymentId).put("order_id", gatewayOrderId)
                .put("status", "captured");
        String payload = new JSONObject().put("event", "payment.captured")
                .put("payload", new JSONObject().put("payment", new JSONObject().put("entity", entity)))
                .toString();
        Resp w = webhookSender.send("POST /api/payments/webhook", HttpRequest.newBuilder(webhookSender.uri("/api/payments/webhook"))
                .header("Content-Type", "application/json")
                .header("X-Razorpay-Signature", "soak_signature")
                .POST(HttpRequest.BodyPublishers.ofString(payload)));
        if (w.status() != 200) {
            fail("webhook for " + gatewayOrderId + " answered " + w.status());
        }
    }

    /** Watches the order the way the order page does, then shows the code at the counter. */
    private void waitForCollection(Session s, OutletFixture outlet, long orderId) throws InterruptedException {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        long deadline = System.nanoTime() + JOURNEY_TIMEOUT.toNanos();
        boolean shownCode = false;
        String status = "?";
        int polls = 0;
        while (System.nanoTime() < deadline) {
            Thread.sleep(r.nextLong(2_000, 3_500));
            Resp st = s.getJson("GET /api/orders/{id}/status", "/api/orders/" + orderId + "/status");
            if (st.status() != 200) {
                fail("status poll for " + orderId + " answered " + st.status());
                return;
            }
            status = new JSONObject(st.body()).getString("status");
            polls++;
            if (polls % 6 == 0) {
                s.get("GET /student/orders/{id}", "/student/orders/" + orderId);
            }
            if (polls % 4 == 0) {
                s.getJson("GET /api/orders/active", "/api/orders/active");
            }
            switch (status) {
                case "READY_FOR_PICKUP" -> {
                    if (!shownCode) {
                        String code = readPickupCode(s, orderId);
                        if (code == null) {
                            fail("order " + orderId + " at " + outlet.name()
                                    + " is ready but no pickup code appeared within 30s: it cannot be handed over");
                            return;
                        }
                        counterAt(outlet.outletId()).put(orderId, code);
                        shownCode = true;
                    }
                }
                case "COMPLETED" -> {
                    count("collected");
                    return;
                }
                case "CANCELLED" -> {
                    count("cancelled by the kitchen");
                    return;
                }
                default -> {
                    // PAID (inside the window, or queued) or PREPARING: keep waiting.
                }
            }
        }
        fail("order " + orderId + " at " + outlet.name() + " stuck in " + status + " for " + JOURNEY_TIMEOUT.toMinutes() + " min");
    }

    /**
     * The order page's pickup code. READY_FOR_PICKUP is committed before the code is
     * written (OrderService.advanceStatus: two separate statements, no transaction), so a
     * student can land in between and see "ready" with no code. They refresh; so does
     * this. Seeing it at all is counted. Never seeing a code is the failure: that order
     * cannot be handed over by anyone.
     */
    private String readPickupCode(Session s, long orderId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        boolean counted = false;
        while (true) {
            Resp page = s.get("GET /student/orders/{id}", "/student/orders/" + orderId);
            Matcher m = PICKUP_CODE.matcher(page.body());
            if (m.find()) {
                return m.group(1);
            }
            if (!counted) {
                count("saw 'ready' before its pickup code existed");
                counted = true;
            }
            if (System.nanoTime() > deadline) {
                return null;
            }
            Thread.sleep(2_000);
        }
    }

    // ── Staff ─────────────────────────────────────────────────────────────────

    private void staffLoop(OutletFixture outlet, Session s, boolean manager) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int tick = 0;
        while (!staffStop.get()) {
            try {
                tick++;
                Resp q = s.getJson("GET /api/orders/queue", "/api/orders/queue");
                if (q.status() != 200) {
                    fail("queue poll at " + outlet.name() + " answered " + q.status());
                    Thread.sleep(2_000);
                    continue;
                }
                if (tick % 10 == 0) {
                    s.get("GET /canteen/queue", "/canteen/queue");
                }
                if (manager && tick % 25 == 0) {
                    s.get("GET /canteen/reports", "/canteen/reports");
                    s.get("GET /canteen/orders", "/canteen/orders");
                }
                JSONArray queue = new JSONArray(q.body());
                List<JSONObject> orders = new ArrayList<>();
                for (int i = 0; i < queue.length(); i++) {
                    orders.add(queue.getJSONObject(i));
                }
                Collections.shuffle(orders);
                for (JSONObject o : orders) {
                    act(outlet, s, o);
                }
                Thread.sleep(r.nextLong(1_500, 2_500));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                if (!staffStop.get()) {
                    fail("staff loop at " + outlet.name() + " threw " + e);
                }
            }
        }
    }

    private void act(OutletFixture outlet, Session s, JSONObject o) {
        long id = o.getLong("id");
        String status = o.getString("status");
        KitchenPlan plan = planFor(id);
        String base = "/canteen/queue/" + id;
        switch (status) {
            case "PAID" -> {
                if (plan == KitchenPlan.CANCEL && claimed.add("cancel:" + id)) {
                    staffPost(s, "POST /canteen/queue/{id}/cancel", base + "/cancel", Map.of("reason", "Soak: gas ran out"));
                    count("kitchen cancelled an order");
                } else if (plan == KitchenPlan.ITEM_CANCEL && o.getJSONArray("lines").length() >= 2
                        && claimed.add("items:" + id)) {
                    long lineId = o.getJSONArray("lines").getJSONObject(0).getLong("id");
                    staffPost(s, "POST /canteen/queue/{id}/items/cancel", base + "/items/cancel",
                            Map.of("lineId", String.valueOf(lineId), "reason", "CANNOT_MAKE"));
                    count("kitchen removed an item");
                } else if (plan != KitchenPlan.CANCEL) {
                    staffPost(s, "POST /canteen/queue/{id}/status", base + "/status", Map.of("newStatus", "PREPARING"));
                    preparingSince.putIfAbsent(id, System.nanoTime());
                }
            }
            case "PREPARING" -> {
                long since = preparingSince.computeIfAbsent(id, k -> System.nanoTime());
                if ((System.nanoTime() - since) / 1_000_000 >= cookMillis.getOrDefault(id, 5_000L)) {
                    staffPost(s, "POST /canteen/queue/{id}/status", base + "/status", Map.of("newStatus", "READY_FOR_PICKUP"));
                }
            }
            case "READY_FOR_PICKUP" -> {
                String code = counterAt(outlet.outletId()).remove(id);
                if (code != null) {
                    staffPost(s, "POST /canteen/queue/{id}/collect", base + "/collect", Map.of("pickupCode", code));
                }
            }
            default -> {
                // Anything else in the queue is somebody else's move.
            }
        }
    }

    /**
     * Two people work each queue, so a second click on an order the first has just moved
     * is normal and must be refused cleanly. 5xx and 403 are failures (recorded in send);
     * a 4xx is counted as a collision; a redirect is the ordinary answer.
     */
    private void staffPost(Session s, String label, String path, Map<String, String> form) {
        Resp r = s.post(label, path, form);
        if (!r.redirect() && r.status() < 500 && r.status() != 403) {
            count("staff collisions (" + r.status() + ")");
        }
    }

    // ── Admin ─────────────────────────────────────────────────────────────────

    private void adminLoop(Session s, AtomicBoolean stop) {
        while (!stop.get()) {
            try {
                s.get("GET /admin", "/admin");
                s.get("GET /admin/analytics", "/admin/analytics");
                Thread.sleep(ThreadLocalRandom.current().nextLong(20_000, 40_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                if (!stop.get()) {
                    fail("admin loop threw " + e);
                }
            }
        }
    }

    // ── Progress ──────────────────────────────────────────────────────────────

    private void tick(HikariPoolMXBean pool) {
        try {
            List<Integer> drained = new ArrayList<>();
            Integer v;
            while ((v = metrics.window.poll()) != null) {
                drained.add(v);
            }
            int[] w = sorted(drained);
            int active = pool.getActiveConnections();
            int waiting = pool.getThreadsAwaitingConnection();
            metrics.maxPoolActive.accumulateAndGet(active, Math::max);
            metrics.maxPoolWaiting.accumulateAndGet(waiting, Math::max);
            Runtime rt = Runtime.getRuntime();
            long heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            metrics.maxHeapMb.accumulateAndGet(heapMb, Math::max);
            Long sessions = jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Long.class);
            long seconds = elapsedMillis() / 1000;
            Interval in = new Interval(seconds, w.length, percentile(w, 0.50), percentile(w, 0.95),
                    w.length == 0 ? 0 : w[w.length - 1], active, waiting, heapMb, sessions == null ? 0 : sessions,
                    counter("orders created"), counter("collected"));
            metrics.intervals.add(in);
            System.out.printf("[soak] %4ds | %5.1f req/s p50 %4dms p95 %4dms max %5dms | pool %d active %d waiting "
                            + "| heap %4dMB | sessions %d | journeys %d | orders %d paid %d collected %d | failures %d%n",
                    seconds, w.length / (double) REPORT_SECONDS, in.p50(), in.p95(), in.max(), active, waiting,
                    heapMb, in.sessions(), journeysInFlight.get(), counter("orders created"), counter("paid"),
                    counter("collected"), failures.size());

            // Live checks, cheap enough to run every tick: the unique keys should make these
            // impossible, and if they ever are not, the moment it happened is worth knowing.
            String ids = tenantIds();
            Integer livePickupDupes = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT outlet_id, active_pickup_code "
                    + "FROM orders WHERE tenant_id IN (" + ids + ") AND active_pickup_code IS NOT NULL "
                    + "GROUP BY outlet_id, active_pickup_code HAVING COUNT(*) > 1) d", Integer.class);
            if (livePickupDupes != null && livePickupDupes > 0) {
                fail("at " + seconds + "s two live orders at one canteen shared a pickup code");
            }
        } catch (RuntimeException e) {
            System.out.println("[soak] progress tick failed: " + e);
        }
    }

    // ── Stall forensics ───────────────────────────────────────────────────────

    private final List<String> stalls = new CopyOnWriteArrayList<>();
    private final AtomicLong lastStallCaptureNanos = new AtomicLong();

    /**
     * When requests queue for a connection, ask MySQL what it is doing: which statements
     * are running and for how long, who is waiting on whose lock, and which transactions
     * are open. Taken over a connection of its own, because the pool is the thing that is
     * full. At most one snapshot per 15s, and at most eight in a run.
     */
    private void captureStall(int active, int waiting) {
        long now = System.nanoTime();
        long last = lastStallCaptureNanos.get();
        if (stalls.size() >= 8 || (last != 0 && now - last < TimeUnit.SECONDS.toNanos(15))
                || !lastStallCaptureNanos.compareAndSet(last, now)) {
            return;
        }
        Thread.ofVirtual().start(() -> {
            StringBuilder out = new StringBuilder();
            out.append(String.format("at %ds: pool %d active, %d waiting%n", elapsedMillis() / 1000, active, waiting));
            try (java.sql.Connection c = java.sql.DriverManager.getConnection(dbUrl, dbUser, dbPassword);
                 java.sql.Statement st = c.createStatement()) {
                dump(st, out, "running statements",
                        "SELECT ID, TIME, STATE, LEFT(REPLACE(INFO, '\n', ' '), 150) AS query FROM information_schema.PROCESSLIST "
                                + "WHERE COMMAND <> 'Sleep' AND INFO NOT LIKE '%PROCESSLIST%' ORDER BY TIME DESC LIMIT 12");
                dump(st, out, "lock waits",
                        "SELECT wait_age, locked_table, locked_index, locked_type, LEFT(waiting_query, 110) AS waiting, "
                                + "LEFT(blocking_query, 110) AS blocking, blocking_trx_age FROM sys.innodb_lock_waits LIMIT 12");
                dump(st, out, "open transactions",
                        "SELECT trx_started, trx_state, trx_rows_locked, trx_rows_modified, LEFT(trx_query, 130) AS query "
                                + "FROM information_schema.INNODB_TRX ORDER BY trx_started LIMIT 10");
            } catch (Exception e) {
                out.append("  snapshot failed: ").append(e).append('\n');
            }
            stalls.add(out.toString());
            System.out.print("[soak] STALL captured\n" + out);
        });
    }

    private static void dump(java.sql.Statement st, StringBuilder out, String title, String sql) throws java.sql.SQLException {
        out.append("  ").append(title).append(":\n");
        try (java.sql.ResultSet rs = st.executeQuery(sql)) {
            int cols = rs.getMetaData().getColumnCount();
            int rows = 0;
            while (rs.next()) {
                StringJoiner line = new StringJoiner(" | ", "    ", "\n");
                for (int i = 1; i <= cols; i++) {
                    line.add(String.valueOf(rs.getObject(i)));
                }
                out.append(line);
                rows++;
            }
            if (rows == 0) {
                out.append("    (none)\n");
            }
        }
    }

    // ── Audit ─────────────────────────────────────────────────────────────────

    private String tenantIds() {
        return colleges.stream().map(c -> String.valueOf(c.tenantId())).collect(Collectors.joining(","));
    }

    private final Map<String, String> auditFacts = new LinkedHashMap<>();

    /** Every check here is one sentence of what must be true of the data a lunch service leaves behind. */
    private List<String> audit() {
        List<String> broken = new ArrayList<>();
        String ids = tenantIds();

        // 1. Every canteen served its floor, and nothing was left mid-flight.
        List<Map<String, Object>> perOutlet = jdbc.queryForList(
                "SELECT ot.name AS outlet, o.outlet_id, "
                        + "SUM(o.status = 'COMPLETED') AS completed, SUM(o.status = 'CANCELLED') AS cancelled, "
                        + "SUM(o.status IN ('AWAITING_PAYMENT','EXPIRED','PAYMENT_FAILED')) AS unpaid, "
                        + "SUM(o.status IN ('PAID','PREPARING','READY_FOR_PICKUP')) AS live, COUNT(*) AS total "
                        + "FROM orders o JOIN outlets ot ON ot.id = o.outlet_id WHERE o.tenant_id IN (" + ids + ") "
                        + "GROUP BY o.outlet_id, ot.name ORDER BY ot.name");
        StringBuilder table = new StringBuilder();
        int outletsSeen = 0;
        for (Map<String, Object> row : perOutlet) {
            outletsSeen++;
            long completed = ((Number) row.get("completed")).longValue();
            long live = ((Number) row.get("live")).longValue();
            table.append(String.format("  %-14s total %4d  completed %4d  cancelled %3d  unpaid %3d  live %d%n",
                    row.get("outlet"), ((Number) row.get("total")).longValue(), completed,
                    ((Number) row.get("cancelled")).longValue(), ((Number) row.get("unpaid")).longValue(), live));
            if (completed < MIN_COMPLETED_PER_OUTLET) {
                broken.add(row.get("outlet") + " completed " + completed + " orders, floor is " + MIN_COMPLETED_PER_OUTLET);
            }
            if (live > 0) {
                broken.add(row.get("outlet") + " was left with " + live + " orders mid-flight");
            }
        }
        auditFacts.put("per canteen", "\n" + table);
        if (outletsSeen != COLLEGES * OUTLETS_PER_COLLEGE) {
            broken.add("orders landed at " + outletsSeen + " canteens, expected " + (COLLEGES * OUTLETS_PER_COLLEGE));
        }

        // 2. Every order that was paid has exactly one payment that captured money.
        Integer badCapture = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders o WHERE o.tenant_id IN (" + ids + ") AND o.paid_at IS NOT NULL "
                        + "AND (SELECT COUNT(*) FROM payments p WHERE p.order_id = o.id "
                        + "     AND p.status IN ('CAPTURED','REFUND_PENDING','REFUNDED')) <> 1", Integer.class);
        check(broken, badCapture, "paid orders without exactly one captured payment");

        Integer paidWithoutStamp = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders o WHERE o.tenant_id IN (" + ids + ") "
                        + "AND o.status IN ('PAID','PREPARING','READY_FOR_PICKUP','COMPLETED') AND o.paid_at IS NULL",
                Integer.class);
        check(broken, paidWithoutStamp, "orders past payment with no paid_at");

        // 3. Money kept equals what the order now says it costs; a cancelled order kept nothing.
        Integer keptMismatch = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders o JOIN payments p ON p.order_id = o.id "
                        + "AND p.status IN ('CAPTURED','REFUND_PENDING','REFUNDED') "
                        + "WHERE o.tenant_id IN (" + ids + ") AND o.status <> 'CANCELLED' "
                        + "AND p.amount - p.refunded_amount <> o.total_amount", Integer.class);
        check(broken, keptMismatch, "orders where captured − refunded ≠ the order's total");

        // A full cancellation is recorded as status REFUNDED; refunded_amount holds only what
        // partial refunds claimed before it (V37). So "refunded in full" is judged by status
        // here, and by what actually reached the gateway just below.
        Integer cancelledNotRefunded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders o JOIN payments p ON p.order_id = o.id "
                        + "AND p.status IN ('CAPTURED','REFUND_PENDING','REFUNDED') "
                        + "WHERE o.tenant_id IN (" + ids + ") AND o.status = 'CANCELLED' AND p.status <> 'REFUNDED'",
                Integer.class);
        check(broken, cancelledNotRefunded, "cancelled paid orders whose payment is not marked refunded");

        // What the gateway was actually asked to send back, per payment: everything for a
        // cancelled order, the partial claims for one with lines removed, nothing otherwise.
        // Double refunds and missed refunds both show up here and nowhere else.
        int gatewayMismatch = 0;
        List<String> examples = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT o.id, o.status, p.razorpay_payment_id AS pid, p.amount, p.refunded_amount "
                        + "FROM orders o JOIN payments p ON p.order_id = o.id "
                        + "AND p.status IN ('CAPTURED','REFUND_PENDING','REFUNDED') "
                        + "WHERE o.tenant_id IN (" + ids + ")")) {
            BigDecimal expected = "CANCELLED".equals(row.get("status"))
                    ? (BigDecimal) row.get("amount") : (BigDecimal) row.get("refunded_amount");
            BigDecimal sent = paymentGateway.refundsFor((String) row.get("pid")).stream()
                    .map(GatewayRefund::amountRupees).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sent.compareTo(expected) != 0) {
                gatewayMismatch++;
                if (examples.size() < 5) {
                    examples.add("order " + row.get("id") + " " + row.get("status") + ": owed back " + expected + ", gateway sent " + sent);
                }
            }
        }
        if (gatewayMismatch > 0) {
            broken.add(gatewayMismatch + " payments where refunds sent to the gateway ≠ what was owed back " + examples);
        }

        Integer overRefunded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments p JOIN orders o ON o.id = p.order_id "
                        + "WHERE o.tenant_id IN (" + ids + ") AND p.refunded_amount > p.amount", Integer.class);
        check(broken, overRefunded, "payments refunded more than they took");

        Integer partialMismatch = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders o JOIN payments p ON p.order_id = o.id "
                        + "AND p.status IN ('CAPTURED','REFUND_PENDING','REFUNDED') "
                        + "WHERE o.tenant_id IN (" + ids + ") AND o.status <> 'CANCELLED' "
                        + "AND EXISTS (SELECT 1 FROM order_items oi WHERE oi.order_id = o.id AND oi.cancelled_at IS NOT NULL) "
                        + "AND p.refunded_amount <> (SELECT COALESCE(SUM(r.amount), 0) FROM order_refunds r WHERE r.order_id = o.id)",
                Integer.class);
        check(broken, partialMismatch, "item cancellations where the payment's refund ≠ the refund records");

        Integer untouchedButRefunded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders o JOIN payments p ON p.order_id = o.id "
                        + "WHERE o.tenant_id IN (" + ids + ") AND o.status = 'COMPLETED' AND p.refunded_amount > 0 "
                        + "AND NOT EXISTS (SELECT 1 FROM order_items oi WHERE oi.order_id = o.id AND oi.cancelled_at IS NOT NULL)",
                Integer.class);
        check(broken, untouchedButRefunded, "completed orders with nothing removed that were refunded anyway");

        Integer reconciliation = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments p JOIN orders o ON o.id = p.order_id "
                        + "WHERE o.tenant_id IN (" + ids + ") AND p.needs_reconciliation = 1", Integer.class);
        check(broken, reconciliation, "payments flagged for manual reconciliation (the stub gateway never fails)");

        // 4. The arithmetic of every total, as BillingService.charges defines it.
        Integer badArithmetic = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE tenant_id IN (" + ids + ") "
                        + "AND total_amount <> food_amount - discount_amount + platform_fee + tip_amount", Integer.class);
        check(broken, badArithmetic, "orders whose total ≠ food − discount + platform fee + tip");

        // 5. Nobody was charged for an order they walked away from.
        Integer chargedAbandoned = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders o JOIN payments p ON p.order_id = o.id "
                        + "WHERE o.tenant_id IN (" + ids + ") AND o.status IN ('AWAITING_PAYMENT','EXPIRED','PAYMENT_FAILED') "
                        + "AND (p.razorpay_payment_id IS NOT NULL OR p.status IN ('CAPTURED','REFUNDED','REFUND_PENDING'))",
                Integer.class);
        check(broken, chargedAbandoned, "unpaid orders carrying a captured payment");

        // 6. Numbers a student is told to show are unique where they must be.
        Integer tokenDupes = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT tenant_id, token_day, token_no FROM orders "
                + "WHERE tenant_id IN (" + ids + ") GROUP BY tenant_id, token_day, token_no HAVING COUNT(*) > 1) d", Integer.class);
        check(broken, tokenDupes, "tokens issued twice in one college on one day");
        Integer completedWithoutCode = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE tenant_id IN (" + ids + ") "
                + "AND status = 'COMPLETED' AND pickup_code IS NULL", Integer.class);
        check(broken, completedWithoutCode, "orders handed over without a pickup code");

        // 6b. Each order reaches each status once. Two staff tapping together must not both
        // win: a second READY_FOR_PICKUP re-issues the pickup code, and the "ready" push
        // already sent to the student carries the first one, which the counter now refuses.
        List<Map<String, Object>> doubled = jdbc.queryForList("SELECT al.action, COUNT(*) AS orders FROM ("
                + "SELECT entity_id, action FROM audit_log WHERE entity_type = 'Order' AND action LIKE 'STATUS_%' "
                + "AND entity_id IN (SELECT id FROM orders WHERE tenant_id IN (" + ids + ")) "
                + "GROUP BY entity_id, action HAVING COUNT(*) > 1) al GROUP BY al.action");
        for (Map<String, Object> row : doubled) {
            broken.add(row.get("orders") + " orders moved to " + String.valueOf(row.get("action")).substring(7)
                    + " twice (concurrent staff taps both succeeded)");
        }
        Long maxCodeLag = jdbc.queryForObject("SELECT MAX(TIMESTAMPDIFF(MICROSECOND, ready_at, pickup_code_issued_at)) "
                + "FROM orders WHERE tenant_id IN (" + ids + ") AND ready_at IS NOT NULL AND pickup_code_issued_at IS NOT NULL",
                Long.class);
        auditFacts.put("longest gap between 'ready' and its pickup code existing",
                " " + (maxCodeLag == null ? "n/a" : (maxCodeLag / 1000) + "ms"));

        // 7. No order or line crossed a college or canteen.
        Integer crossTenant = jdbc.queryForObject("SELECT COUNT(*) FROM orders o JOIN users u ON u.id = o.user_id "
                + "JOIN outlets ot ON ot.id = o.outlet_id WHERE o.tenant_id IN (" + ids + ") "
                + "AND (u.tenant_id <> o.tenant_id OR ot.tenant_id <> o.tenant_id)", Integer.class);
        check(broken, crossTenant, "orders whose student or canteen is in another college");
        Integer crossOutlet = jdbc.queryForObject("SELECT COUNT(*) FROM order_items oi JOIN orders o ON o.id = oi.order_id "
                + "JOIN menu_items mi ON mi.id = oi.menu_item_id WHERE o.tenant_id IN (" + ids + ") "
                + "AND mi.outlet_id <> o.outlet_id", Integer.class);
        check(broken, crossOutlet, "order lines for a dish from a different canteen");

        // 8. A promo never went past its cap, or twice to one student.
        String promoIds = colleges.stream().map(c -> String.valueOf(c.promoId())).collect(Collectors.joining(","));
        String live = "EXISTS (SELECT 1 FROM orders lo WHERE lo.id = r.order_id AND lo.status NOT IN ('PAYMENT_FAILED','EXPIRED','CANCELLED'))";
        List<Map<String, Object>> promoUse = jdbc.queryForList("SELECT p.code, p.max_redemptions, "
                + "(SELECT COUNT(*) FROM promo_redemptions r WHERE r.promo_code_id = p.id AND " + live + ") AS uses "
                + "FROM promo_codes p WHERE p.id IN (" + promoIds + ")");
        StringBuilder promos = new StringBuilder();
        for (Map<String, Object> row : promoUse) {
            long uses = ((Number) row.get("uses")).longValue();
            long cap = ((Number) row.get("max_redemptions")).longValue();
            promos.append(String.format("  %s %d/%d%n", row.get("code"), uses, cap));
            if (uses > cap) {
                broken.add("promo " + row.get("code") + " used " + uses + " times against a cap of " + cap);
            }
        }
        auditFacts.put("promo use (live)", "\n" + promos);
        Integer perUser = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT r.promo_code_id, r.user_id FROM promo_redemptions r "
                + "WHERE r.promo_code_id IN (" + promoIds + ") AND " + live + " GROUP BY r.promo_code_id, r.user_id "
                + "HAVING COUNT(*) > 1) d", Integer.class);
        check(broken, perUser, "students who used a one-per-student promo more than once");

        // 9. Reported, not asserted: the daily cap is best-effort by design.
        List<Map<String, Object>> caps = jdbc.queryForList("SELECT ot.name AS outlet, mi.daily_limit AS cap, "
                + "COALESCE(SUM(oi.quantity), 0) AS sold FROM menu_items mi JOIN outlets ot ON ot.id = mi.outlet_id "
                + "LEFT JOIN order_items oi ON oi.menu_item_id = mi.id AND oi.cancelled_at IS NULL "
                + "LEFT JOIN orders o ON o.id = oi.order_id "
                + "WHERE mi.tenant_id IN (" + ids + ") AND mi.daily_limit IS NOT NULL "
                + "AND (o.id IS NULL OR o.status NOT IN ('CANCELLED','EXPIRED','PAYMENT_FAILED')) "
                + "GROUP BY mi.id, ot.name, mi.daily_limit ORDER BY ot.name");
        StringBuilder capLines = new StringBuilder();
        long overshootTotal = 0;
        for (Map<String, Object> row : caps) {
            long sold = ((Number) row.get("sold")).longValue();
            long cap = ((Number) row.get("cap")).longValue();
            overshootTotal += Math.max(0, sold - cap);
            capLines.append(String.format("  %-14s sold %3d of %3d%s%n", row.get("outlet"), sold, cap,
                    sold > cap ? "  (over by " + (sold - cap) + ")" : ""));
        }
        auditFacts.put("capped dish (best-effort cap, reported only)", "\n" + capLines
                + "  total overshoot across canteens: " + overshootTotal);
        return broken;
    }

    private static void check(List<String> broken, Integer count, String what) {
        if (count != null && count > 0) {
            broken.add(count + " " + what);
        }
    }

    // ── Report ────────────────────────────────────────────────────────────────

    private String report(long runSeconds, List<String> invariantFailures) {
        StringBuilder out = new StringBuilder();
        out.append("\n================ BiteSite order soak: run ").append(runId).append(" ================\n");
        out.append(String.format("seed %d | %d colleges × %d canteens × %d arrivals | load %d min, total %ds%n",
                SEED, COLLEGES, OUTLETS_PER_COLLEGE, ORDERS_PER_OUTLET, MINUTES, runSeconds));
        out.append(String.format("students %d/college, staff %d/canteen, hikari max 10, JVM %s%n%n",
                STUDENTS_PER_COLLEGE, STAFF_PER_OUTLET, Runtime.version()));

        long total = metrics.endpoints.values().stream().mapToLong(e -> e.count.sum()).sum();
        long samples = metrics.poolSamples.sum();
        out.append(String.format("requests %,d (%.1f/s average) | pool max active %d of 10, max waiting %d, "
                        + "someone waiting in %.2f%% of %,d samples | heap max %dMB | threads %d%n%n",
                total, total / (double) Math.max(1, runSeconds), metrics.maxPoolActive.get(),
                metrics.maxPoolWaiting.get(), samples == 0 ? 0 : 100.0 * metrics.poolSaturatedSamples.sum() / samples,
                samples, metrics.maxHeapMb.get(), ManagementFactory.getThreadMXBean().getThreadCount()));

        out.append(String.format("%-40s %8s %7s %7s %7s %7s  %s%n", "endpoint", "count", "p50", "p95", "p99", "max", "status codes"));
        metrics.endpoints.entrySet().stream()
                .sorted(Map.Entry.<String, Endpoint>comparingByValue(Comparator.comparingLong(e -> -e.count.sum())))
                .forEach(e -> {
                    int[] ms = sorted(e.getValue().millis);
                    String codes = e.getValue().statuses.entrySet().stream().sorted(Map.Entry.comparingByKey())
                            .map(s -> s.getKey() + "×" + s.getValue().sum()).collect(Collectors.joining(" "));
                    out.append(String.format("%-40s %8d %5dms %5dms %5dms %5dms  %s%n", e.getKey(), e.getValue().count.sum(),
                            percentile(ms, 0.50), percentile(ms, 0.95), percentile(ms, 0.99),
                            ms.length == 0 ? 0 : ms[ms.length - 1], codes));
                });

        out.append("\nhow the day went\n");
        counters.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.append(String.format("  %-44s %6d%n", e.getKey(), e.getValue().sum())));

        // Drift: the same load shape should cost the same at the end as at the start.
        List<Interval> busy = metrics.intervals.stream().filter(i -> i.requests() > 0).toList();
        if (busy.size() >= 4) {
            int q = Math.max(1, busy.size() / 4);
            double first = busy.subList(0, q).stream().mapToInt(Interval::p95).average().orElse(0);
            double last = busy.subList(busy.size() - q, busy.size()).stream().mapToInt(Interval::p95).average().orElse(0);
            out.append(String.format("%nlatency drift: p95 first quarter %.0fms, last quarter %.0fms%n", first, last));
        }
        out.append("\ntimeline\n");
        for (Interval i : metrics.intervals) {
            out.append(String.format("  %5ds  %6.1f req/s  p50 %4dms  p95 %4dms  max %5dms  pool %2d/%2d  heap %4dMB  sessions %5d  orders %5d  collected %5d%n",
                    i.atSecond(), i.requests() / (double) REPORT_SECONDS, i.p50(), i.p95(), i.max(), i.poolActive(),
                    i.poolWaiting(), i.heapMb(), i.sessions(), i.ordersCreated(), i.completed()));
        }

        out.append("\naudit\n");
        auditFacts.forEach((k, v) -> out.append("  ").append(k).append(":").append(v).append("\n"));
        if (invariantFailures.isEmpty()) {
            out.append("  every invariant held\n");
        } else {
            invariantFailures.forEach(f -> out.append("  BROKEN: ").append(f).append("\n"));
        }

        if (!stalls.isEmpty()) {
            out.append("\nstall forensics (pool had 5+ requests waiting)\n");
            stalls.forEach(st -> out.append(st.indent(2)));
        }
        out.append("\nrequest-level failures: ").append(failures.size()).append("\n");
        failures.stream().limit(30).forEach(f -> out.append("  ").append(f).append("\n"));
        out.append("=====================================================================\n");
        return out.toString();
    }
}
