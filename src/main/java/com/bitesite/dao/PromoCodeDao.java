package com.bitesite.dao;

import com.bitesite.model.PromoCode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PromoCodeDao {

    List<PromoCode> findAll();

    Optional<PromoCode> findById(Long id);

    /** Lookup for redemption. Case-insensitive: nobody types a code the way it was stored. */
    Optional<PromoCode> findByCode(String code);

    PromoCode save(PromoCode code);

    void setActive(Long id, boolean active);

    void delete(Long id);

    /**
     * Uses that still count against the campaign budget: redemption rows whose order has
     * not failed, expired or been cancelled. Counted from the rows, never from a stored
     * counter that can drift.
     */
    int countRedemptions(Long promoCodeId);

    int countRedemptionsByUser(Long promoCodeId, Long userId);

    /**
     * Every redemption row ever written, alive or not. This is the deletion guard: a row
     * points at the code with a foreign key, so a code any order ever referenced cannot be
     * removed regardless of whether that order went on to be paid.
     */
    int countAllRedemptions(Long promoCodeId);

    void recordRedemption(Long promoCodeId, Long orderId, Long userId, BigDecimal discountAmount);
}
