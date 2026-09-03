package com.bitesite.dto;

import java.util.List;

/**
 * One page of a list, plus enough to draw the controls around it.
 *
 * <p>Every list in the admin and outlet consoles used to be a bare {@code LIMIT n}: the
 * hundred most recent orders, the hundred most recent payments, two hundred rows of outlet
 * history. Two of those screens said "showing the most recent 100" underneath; the audit
 * log and the outlet history said nothing at all. Either way there was no page two, so the
 * hundred-and-first record was not reachable through the product — you queried the database
 * or you did without.
 *
 * <p>{@code hasNext} is derived by asking the database for one row more than the page needs
 * and seeing whether it comes back, which avoids a second {@code COUNT(*)} over the same
 * predicate on every page view. The extra row is dropped before the page is built, so
 * callers never see it.
 */
public record Paged<T>(List<T> items, int page, int size, boolean hasNext) {

    /** Builds a page from a query that was asked for {@code size + 1} rows. */
    public static <T> Paged<T> of(List<T> rowsPlusOne, int page, int size) {
        boolean hasNext = rowsPlusOne.size() > size;
        List<T> items = hasNext ? List.copyOf(rowsPlusOne.subList(0, size)) : List.copyOf(rowsPlusOne);
        return new Paged<>(items, page, size, hasNext);
    }

    public boolean hasPrev() {
        return page > 0;
    }

    /** 1-based, for display. Page numbering is 0-based everywhere else so it can be
     * multiplied straight into an OFFSET without an off-by-one at each call site. */
    public int displayPage() {
        return page + 1;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** Rows skipped to reach this page — the OFFSET a DAO should apply. */
    public static int offsetFor(int page, int size) {
        return Math.max(0, page) * size;
    }
}
