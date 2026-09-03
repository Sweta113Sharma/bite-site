package com.bitesite.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole design rests on one trick — ask for one row more than the page needs, and use
 * its presence to decide whether a "next" button appears. Get that wrong by one and either
 * the last page offers a next that leads nowhere, or a page of results is silently
 * unreachable.
 */
class PagedTest {

    private static List<Integer> rows(int n) {
        return IntStream.range(0, n).boxed().toList();
    }

    @Test
    void aFullPagePlusOneMeansThereIsAnotherPage() {
        Paged<Integer> paged = Paged.of(rows(11), 0, 10);

        assertThat(paged.items()).hasSize(10);
        assertThat(paged.hasNext()).isTrue();
        // The probe row must never reach the caller.
        assertThat(paged.items()).doesNotContain(10);
    }

    @Test
    void anExactlyFullPageIsTheLastPage() {
        Paged<Integer> paged = Paged.of(rows(10), 0, 10);

        assertThat(paged.items()).hasSize(10);
        assertThat(paged.hasNext()).isFalse();
    }

    @Test
    void aPartialPageIsTheLastPage() {
        Paged<Integer> paged = Paged.of(rows(3), 0, 10);

        assertThat(paged.items()).hasSize(3);
        assertThat(paged.hasNext()).isFalse();
    }

    @Test
    void anEmptyResultHasNeitherDirection() {
        Paged<Integer> paged = Paged.of(List.of(), 0, 10);

        assertThat(paged.isEmpty()).isTrue();
        assertThat(paged.hasNext()).isFalse();
        assertThat(paged.hasPrev()).isFalse();
    }

    @Test
    void thereIsNoPreviousPageFromTheFirstOne() {
        assertThat(Paged.of(rows(11), 0, 10).hasPrev()).isFalse();
        assertThat(Paged.of(rows(11), 1, 10).hasPrev()).isTrue();
    }

    @Test
    void offsetsSkipWholePages() {
        assertThat(Paged.offsetFor(0, 50)).isZero();
        assertThat(Paged.offsetFor(1, 50)).isEqualTo(50);
        assertThat(Paged.offsetFor(3, 50)).isEqualTo(150);
    }

    /** A hand-typed ?page=-5 must not become a negative OFFSET, which MySQL rejects. */
    @Test
    void aNegativePageCannotProduceANegativeOffset() {
        assertThat(Paged.offsetFor(-5, 50)).isZero();
    }

    @Test
    void displayPageIsOneBasedForHumans() {
        assertThat(Paged.of(rows(3), 0, 10).displayPage()).isEqualTo(1);
        assertThat(Paged.of(rows(3), 4, 10).displayPage()).isEqualTo(5);
    }
}
