package com.bitesite.dao;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every paginated query must order by something unique, or the pages lie.
 *
 * <p>{@code created_at} is second-precision, so rows written in the same second tie. MySQL
 * is free to break ties differently between two executions of the same query, which means
 * a list paginated on {@code created_at} alone can show a row twice on page two and never
 * show another one at all. On the audit log that is a record silently missing from a
 * screen whose entire job is to have every record.
 *
 * <p>Eight queries had this. It surfaced when a new test failed on CI while passing
 * locally, three times in a row, with every timestamp deliberately tied: this machine's
 * MySQL returns ties in insertion order and CI's does not. So a behavioural test cannot be
 * trusted to catch it — whether it fails depends on which MySQL you run it against, and the
 * one that lets it pass is the one on your laptop.
 *
 * <p>Hence a structural check. Reading the SQL is the only way to assert this that does not
 * depend on a particular engine's tie behaviour.
 */
class PaginationOrderingTest {

    private static final Path DAO = Path.of("src/main/java/com/bitesite/dao");

    /** An ORDER BY immediately followed by the paging clause, i.e. the sort paging relies on. */
    private static final Pattern PAGED_ORDER_BY = Pattern.compile(
            "ORDER BY ([^\"']*?)\\s+LIMIT \\?\\s+OFFSET \\?");

    /** A unique column ends the sort: bare `id`, or qualified like `o.id`. */
    private static final Pattern ENDS_WITH_ID = Pattern.compile(
            ",\\s*(?:[a-z]+\\.)?id\\s+(?:ASC|DESC)?\\s*$", Pattern.CASE_INSENSITIVE);

    private static boolean isTotalOrder(String orderBy) {
        String trimmed = orderBy.trim();
        // Ordering by a unique column on its own is already total.
        if (trimmed.matches("(?i)(?:[a-z]+\\.)?id\\s*(?:ASC|DESC)?")) {
            return true;
        }
        return ENDS_WITH_ID.matcher(trimmed).find();
    }

    @Test
    void everyPaginatedQueryOrdersByAUniqueColumnLast() throws IOException {
        List<String> unstable = new ArrayList<>();

        try (Stream<Path> files = Files.walk(DAO)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                // Java string concatenation splits SQL across lines; rejoin before matching.
                String sql = src.replaceAll("\"\\s*\\+\\s*\"", " ").replaceAll("\\s+", " ");

                Matcher m = PAGED_ORDER_BY.matcher(sql);
                while (m.find()) {
                    if (!isTotalOrder(m.group(1))) {
                        unstable.add(f.getFileName() + ": ORDER BY " + m.group(1).trim());
                    }
                }
            }
        }

        assertThat(unstable)
                .as("these queries page over a sort that is not total, so rows sharing the "
                        + "sort value can repeat on one page and be skipped on another. "
                        + "Add a unique tiebreaker, e.g. ORDER BY created_at DESC, id DESC")
                .isEmpty();
    }

    /** The check is only worth having if it is actually finding the paginated queries. */
    @Test
    void theScanFindsThePaginatedQueriesItIsMeantToCheck() throws IOException {
        int found = 0;
        try (Stream<Path> files = Files.walk(DAO)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String sql = Files.readString(f, StandardCharsets.UTF_8)
                        .replaceAll("\"\\s*\\+\\s*\"", " ").replaceAll("\\s+", " ");
                Matcher m = PAGED_ORDER_BY.matcher(sql);
                while (m.find()) {
                    found++;
                }
            }
        }
        assertThat(found)
                .as("the pattern stopped matching the paginated queries, so the assertion "
                        + "above is passing on an empty set rather than on correct SQL")
                .isGreaterThanOrEqualTo(8);
    }
}
