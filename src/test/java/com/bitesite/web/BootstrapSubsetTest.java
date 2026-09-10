package com.bitesite.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bootstrap is served as a subset: {@code bootstrap-5.3.3.full.css} is the vendored
 * original and is never linked, and {@code bootstrap.min.css} is generated from it by
 * {@code scripts/build-bootstrap-subset.py}. That takes ~232KB down to ~49KB, which is
 * 31KB of gzip down to 9KB on every first visit.
 *
 * <p>The failure mode is silent and it is the whole reason this test exists. Use a
 * Bootstrap class in a template that the last purge did not know about, and the class is
 * simply not in the served file. Nothing errors. The page renders, just wrong: an
 * unstyled button, a badge with no colour, a grid that does not lay out. It looks like a
 * CSS mistake rather than a missing build step, which is the worst way to spend an
 * afternoon.
 *
 * <p>So: every Bootstrap class the app references anywhere must still be defined in the
 * file the pages actually load. If this fails, run the build script.
 */
class BootstrapSubsetTest {

    private static final Path VENDOR = Path.of("src/main/resources/static/css/vendor");
    private static final Path FULL = VENDOR.resolve("bootstrap-5.3.3.full.css");
    private static final Path SERVED = VENDOR.resolve("bootstrap.min.css");

    /** class="..." in a template, and className/class in rendered JS strings. */
    private static final Pattern CLASS_ATTR = Pattern.compile("class(?:Name)?\\s*=\\s*\"([^\"]*)\"");
    /** th:class / th:classappend build names from quoted literals. */
    private static final Pattern TH_CLASS = Pattern.compile("th:(?:class|classappend)\\s*=\\s*\"([^\"]*)\"");
    /** Class names picked from a lookup table at runtime, e.g. badgeClass = 'bg-success'. */
    private static final Pattern JS_LITERAL = Pattern.compile("'(bg-[a-z-]+|btn-[a-z-]+|text-[a-z-]+)'");

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");

    private static Set<String> referencedClasses() throws IOException {
        Set<String> out = new LinkedHashSet<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Path.of("src/main/resources/templates"))) {
            s.filter(p -> p.toString().endsWith(".html")).forEach(files::add);
        }
        try (Stream<Path> s = Files.list(Path.of("src/main/resources/static/js"))) {
            s.filter(p -> p.toString().endsWith(".js")).forEach(files::add);
        }
        try (Stream<Path> s = Files.list(Path.of("src/main/resources/static"))) {
            s.filter(p -> p.toString().endsWith(".html")).forEach(files::add);
        }

        for (Path f : files) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            addTokens(out, CLASS_ATTR.matcher(text));
            addTokens(out, TH_CLASS.matcher(text));
            Matcher lit = JS_LITERAL.matcher(text);
            while (lit.find()) {
                out.add(lit.group(1));
            }
        }
        return out;
    }

    private static void addTokens(Set<String> out, Matcher m) {
        while (m.find()) {
            Matcher t = TOKEN.matcher(m.group(1));
            while (t.find()) {
                out.add(t.group());
            }
        }
    }

    /** True when the stylesheet has a rule for this class, not merely the text of it. */
    private static boolean defines(String css, String cls) {
        return Pattern.compile("\\." + Pattern.quote(cls) + "(?![A-Za-z0-9_-])").matcher(css).find();
    }

    @Test
    void everyBootstrapClassTheAppUsesSurvivedTheSubset() throws IOException {
        String full = Files.readString(FULL, StandardCharsets.UTF_8);
        String served = Files.readString(SERVED, StandardCharsets.UTF_8);

        List<String> lost = new ArrayList<>();
        for (String cls : referencedClasses()) {
            // Only classes Bootstrap actually defines are its problem; the rest are ours.
            if (defines(full, cls) && !defines(served, cls)) {
                lost.add(cls);
            }
        }

        assertThat(lost)
                .as("these classes are used in the app and defined by Bootstrap, but are missing "
                        + "from the subset the pages load — so they will render unstyled. "
                        + "Run: python3 scripts/build-bootstrap-subset.py")
                .isEmpty();
    }

    /** The subset is only worth its complexity while it is actually much smaller. */
    @Test
    void theSubsetIsSubstantiallySmallerThanTheFullFile() throws IOException {
        long full = Files.size(FULL);
        long served = Files.size(SERVED);

        assertThat(served)
                .as("bootstrap.min.css looks like the full file — the purge step was probably "
                        + "skipped and the original copied over it")
                .isLessThan(full / 2);
    }

    /** The original has to stay, or there is nothing left to regenerate the subset from. */
    @Test
    void theFullVendoredCopyIsStillPresentToRebuildFrom() {
        assertThat(FULL).exists();
        assertThat(SERVED).exists();
    }
}
