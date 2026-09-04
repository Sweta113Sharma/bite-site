package com.bitesite.service;

import com.bitesite.dao.SavedCartDao;
import com.bitesite.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules that stop a restored cart doing damage: it never overwrites a cart in
 * progress, it never restores items without the outlet they belong to, and it never tries
 * more than once per session.
 */
@ExtendWith(MockitoExtension.class)
class CartPersistenceTest {

    private static final User STUDENT = User.builder().id(7L).build();

    @Mock private SavedCartDao savedCartDao;

    private CartPersistence persistence;
    private Cart cart;

    @BeforeEach
    void setUp() {
        persistence = new CartPersistence(savedCartDao);
        cart = new Cart();
    }

    @Test
    void aSavedCartIsRestoredIntoAnEmptySession() {
        when(savedCartDao.findOutletId(7L)).thenReturn(4L);
        when(savedCartDao.findItems(7L)).thenReturn(Map.of(11L, 2));

        persistence.hydrateOnce(STUDENT, cart);

        assertThat(cart.getOutletId()).isEqualTo(4L);
        assertThat(cart.getQuantities()).containsEntry(11L, 2);
    }

    /** Restoring over a cart someone is building would silently replace their choices. */
    @Test
    void aCartInProgressIsNeverOverwritten() {
        cart.ensureOutlet(9L);
        cart.add(22L, 1);

        persistence.hydrateOnce(STUDENT, cart);

        assertThat(cart.getOutletId()).isEqualTo(9L);
        assertThat(cart.getQuantities()).containsOnlyKeys(22L);
        verify(savedCartDao, never()).findItems(7L);
    }

    @Test
    void restoringIsAttemptedOncePerSessionNotOncePerPage() {
        when(savedCartDao.findOutletId(7L)).thenReturn(null);

        persistence.hydrateOnce(STUDENT, cart);
        persistence.hydrateOnce(STUDENT, cart);
        persistence.hydrateOnce(STUDENT, cart);

        verify(savedCartDao).findOutletId(7L);
    }

    @Test
    void anEmptyCartClearsWhatWasSavedRatherThanSavingNothing() {
        persistence.persist(STUDENT, cart);

        verify(savedCartDao).clear(7L);
    }

    @Test
    void aCartWithItemsIsSavedAgainstItsOutlet() {
        cart.ensureOutlet(4L);
        cart.add(11L, 3);

        persistence.persist(STUDENT, cart);

        Map<Long, Integer> expected = new LinkedHashMap<>();
        expected.put(11L, 3);
        verify(savedCartDao).save(7L, 4L, expected);
    }

    /** Saving is a convenience for later; the cart in the session is what is being used
     * now. A database problem must not fail somebody's add-to-cart. */
    @Test
    void aFailureToSaveDoesNotBreakTheCart() {
        cart.ensureOutlet(4L);
        cart.add(11L, 1);
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(savedCartDao).save(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        persistence.persist(STUDENT, cart);

        assertThat(cart.getQuantities()).containsEntry(11L, 1);
    }
}
