package com.bitesite.service;

import com.bitesite.dao.PromoCodeDao;
import com.bitesite.exception.BusinessException;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.PromoCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Promo codes: what they are worth, and every reason one might be refused.
 *
 * <p>Every check lives here rather than beside a caller, because a code is money and the
 * checks are the only thing standing between a campaign and somebody spending it a
 * thousand times.
 *
 * <p>Refusals are specific on purpose. "That code isn't valid" for an expired code, a
 * spent code and a code that needs a bigger order teaches nobody anything and generates a
 * support message; saying which one it is lets the student fix it themselves. There is no
 * enumeration risk here worth trading that for — a code is not a password, and knowing
 * that SAVE20 expired is not knowing a secret.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PromoCodeService {

    private final PromoCodeDao promoCodeDao;
    private final AuditService auditService;

    /** A validated code and what it is worth on this particular order. */
    public record Applied(PromoCode code, BigDecimal discount) {}

    public List<PromoCode> listAll() {
        return promoCodeDao.findAll();
    }

    public PromoCode get(Long id) {
        return promoCodeDao.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promo code not found"));
    }

    /**
     * Checks a code against one order and works out the discount.
     *
     * @throws BusinessException naming the specific reason it cannot be used
     */
    public Applied validate(String rawCode, Long userId, Long tenantId, Long outletId, BigDecimal foodAmount) {
        if (rawCode == null || rawCode.isBlank()) {
            throw new BusinessException("Enter a code.");
        }
        PromoCode code = promoCodeDao.findByCode(rawCode)
                .orElseThrow(() -> new BusinessException("That code does not exist."));

        if (!code.isActive()) {
            throw new BusinessException("That code is no longer active.");
        }
        if (!code.withinWindow(LocalDateTime.now())) {
            throw new BusinessException("That code has expired or has not started yet.");
        }
        if (!code.appliesTo(tenantId, outletId)) {
            throw new BusinessException("That code cannot be used at this canteen.");
        }
        if (!code.meetsMinimum(foodAmount)) {
            throw new BusinessException("That code needs an order of at least ₹" + code.getMinOrderValue() + ".");
        }
        // Counted from the redemption rows, so two devices cannot both read a stale counter.
        if (code.getMaxRedemptions() != null
                && promoCodeDao.countRedemptions(code.getId()) >= code.getMaxRedemptions()) {
            throw new BusinessException("That code has been fully claimed.");
        }
        if (code.getMaxPerUser() != null
                && promoCodeDao.countRedemptionsByUser(code.getId(), userId) >= code.getMaxPerUser()) {
            throw new BusinessException("You have already used that code.");
        }

        BigDecimal discount = code.discountOn(foodAmount);
        if (discount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("That code is worth nothing on this order.");
        }
        return new Applied(code, discount);
    }

    /**
     * Writes the redemption once the order exists.
     *
     * <p>The unique constraint on order_id is what actually enforces one code per order:
     * two requests racing the same checkout both pass validation, and the database refuses
     * the second. A check-then-insert in Java would not.
     */
    public void redeem(PromoCode code, Long orderId, Long userId, BigDecimal discount) {
        try {
            promoCodeDao.recordRedemption(code.getId(), orderId, userId, discount);
        } catch (DuplicateKeyException e) {
            // Already recorded for this order — the other request won. Nothing to undo.
            log.warn("Duplicate redemption of {} for order {} ignored", code.getCode(), orderId);
        }
    }

    public PromoCode save(PromoCode code, Long actorUserId) {
        if (code.getCode() == null || code.getCode().isBlank()) {
            throw new BusinessException("A code needs some text students can type.");
        }
        if (code.getDiscountValue() == null || code.getDiscountValue().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("A discount has to be worth something.");
        }
        if (code.getDiscountType() == PromoCode.Type.PERCENT
                && code.getDiscountValue().compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessException("A percentage discount cannot exceed 100%.");
        }
        // Not fatal, but it is the mistake that turns a campaign into an open cheque, and
        // an admin should have to say they meant it by setting a ceiling.
        if (code.getDiscountType() == PromoCode.Type.PERCENT && code.getMaxDiscount() == null) {
            throw new BusinessException("A percentage discount needs a maximum, or one large order could cost you anything.");
        }
        if (code.getValidFrom() != null && code.getValidUntil() != null
                && code.getValidUntil().isBefore(code.getValidFrom())) {
            throw new BusinessException("That code would end before it starts.");
        }
        try {
            PromoCode saved = promoCodeDao.save(code);
            auditService.record(actorUserId, code.getTenantId(), "PromoCode", saved.getId(),
                    code.getId() == null ? "CREATE" : "UPDATE", null, saved);
            return saved;
        } catch (DuplicateKeyException e) {
            throw new BusinessException("A code with that text already exists.");
        }
    }

    public void setActive(Long id, boolean active, Long actorUserId) {
        PromoCode code = get(id);
        promoCodeDao.setActive(id, active);
        auditService.record(actorUserId, code.getTenantId(), "PromoCode", id,
                active ? "ACTIVATE" : "DEACTIVATE", !active, active);
    }

    /**
     * Removes a code that was never used.
     *
     * <p>A redeemed code is refused rather than cascaded: the record of what a student was
     * given has to outlive the campaign, both for the settlement and for answering "why
     * was I charged that". Switching it off is the right move for a live one.
     */
    public void delete(Long id, Long actorUserId) {
        PromoCode code = get(id);
        // Every row, not just the live ones: a redemption against an abandoned checkout
        // still holds a foreign key to this code, and the delete would fail at the database.
        int used = promoCodeDao.countAllRedemptions(id);
        if (used > 0) {
            throw new BusinessException("That code has been used " + used
                    + " time(s), so it cannot be deleted — switch it off instead.");
        }
        promoCodeDao.delete(id);
        auditService.record(actorUserId, code.getTenantId(), "PromoCode", id, "DELETE", code, null);
    }
}
