package com.bitesite.service;

import com.bitesite.dao.PlatformSettingsDao;
import com.bitesite.model.BillingSettings;
import com.bitesite.model.Outlet;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a checkout costs and what the platform is owed for it. Wrong here means a canteen
 * is paid the wrong amount or a student is charged one.
 */
class BillingServiceTest {

    /** A settings table with only what a test cares about; everything else stays inert. */
    private static BillingService serviceWith(Map<String, String> settings) {
        return new BillingService(new PlatformSettingsDao() {
            public Map<String, String> findAll() { return settings; }
            public void upsert(String key, String value) { settings.put(key, value); }
        });
    }

    private static Map<String, String> settings(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Outlet outletAt(String commissionPercent) {
        return Outlet.builder().id(1L).name("Canteen")
                .commissionPercent(commissionPercent == null ? null : new BigDecimal(commissionPercent))
                .build();
    }

    // ---- everything is off until somebody turns it on -----------------------

    @Test
    void withNothingConfiguredAnOrderCostsExactlyItsFood() {
        BillingService.Charges c = serviceWith(settings())
                .charges(new BigDecimal("100.00"), outletAt(null), new BigDecimal("10"));

        assertThat(c.total()).isEqualByComparingTo("100.00");
        assertThat(c.platformFee()).isEqualByComparingTo("0.00");
        assertThat(c.tip()).isEqualByComparingTo("0.00");
        assertThat(c.commissionAmount()).isEqualByComparingTo("0.00");
    }

    // ---- commission ---------------------------------------------------------

    @Test
    void commissionUsesThePlatformDefaultWhenTheCanteenHasNotNegotiated() {
        BillingService.Charges c = serviceWith(settings(BillingSettings.COMMISSION_PERCENT, "3"))
                .charges(new BigDecimal("200.00"), outletAt(null), null);

        assertThat(c.commissionPercent()).isEqualByComparingTo("3");
        assertThat(c.commissionAmount()).isEqualByComparingTo("6.00");
    }

    /** The whole reason the override is nullable: a negotiated rate must win. */
    @Test
    void aCanteensOwnRateBeatsTheDefault() {
        BillingService.Charges c = serviceWith(settings(BillingSettings.COMMISSION_PERCENT, "3"))
                .charges(new BigDecimal("200.00"), outletAt("10"), null);

        assertThat(c.commissionAmount()).isEqualByComparingTo("20.00");
    }

    /** A negotiated zero is not the same as "unset", and must survive the default moving. */
    @Test
    void aNegotiatedZeroIsNotTreatedAsUnset() {
        BillingService.Charges c = serviceWith(settings(BillingSettings.COMMISSION_PERCENT, "9"))
                .charges(new BigDecimal("200.00"), outletAt("0"), null);

        assertThat(c.commissionAmount()).isEqualByComparingTo("0.00");
    }

    /**
     * Commission is the canteen's food only. Taking a cut of the platform's own fee would
     * be charging itself, and taking one of a tip would hand the canteen money a student
     * gave to somebody else.
     */
    @Test
    void commissionIsTakenOnFoodAloneNotOnTheFeeOrTheTip() {
        BillingService.Charges c = serviceWith(settings(
                        BillingSettings.COMMISSION_PERCENT, "10",
                        BillingSettings.FEE_AMOUNT, "5", BillingSettings.FEE_CHARGE, "true",
                        BillingSettings.TIP_ENABLED, "true", BillingSettings.TIP_AMOUNTS, "20"))
                .charges(new BigDecimal("100.00"), outletAt(null), new BigDecimal("20"));

        assertThat(c.total()).isEqualByComparingTo("125.00");
        // 10% of the food, not of the 125 that was collected.
        assertThat(c.commissionAmount()).isEqualByComparingTo("10.00");
    }

    // ---- who pays for a discount --------------------------------------------

    /**
     * The one that decides whether canteens end up funding the platform's marketing. A
     * platform-funded discount leaves the canteen sold at full price, so it is owed
     * commission on full price and the platform absorbs the ₹20 out of its own margin.
     */
    @Test
    void aPlatformFundedDiscountLeavesTheCanteenOwedOnTheFullAmount() {
        BillingService.Charges c = serviceWith(settings(BillingSettings.COMMISSION_PERCENT, "10"))
                .charges(new BigDecimal("200.00"), outletAt(null), null,
                        new BigDecimal("20.00"), "PLATFORM");

        assertThat(c.total()).isEqualByComparingTo("180.00");
        // 10% of 200, not of 180.
        assertThat(c.commissionAmount()).isEqualByComparingTo("20.00");
    }

    /** The canteen chose to discount its own food, so its revenue really did fall. */
    @Test
    void aCanteenFundedDiscountPullsTheCommissionDownWithIt() {
        BillingService.Charges c = serviceWith(settings(BillingSettings.COMMISSION_PERCENT, "10"))
                .charges(new BigDecimal("200.00"), outletAt(null), null,
                        new BigDecimal("20.00"), "CANTEEN");

        assertThat(c.total()).isEqualByComparingTo("180.00");
        // 10% of 180.
        assertThat(c.commissionAmount()).isEqualByComparingTo("18.00");
    }

    /** The funder is only recorded when there is actually something to fund. */
    @Test
    void noDiscountMeansNoFunderRecordedOnTheOrder() {
        BillingService.Charges c = serviceWith(settings())
                .charges(new BigDecimal("100.00"), outletAt(null), null, BigDecimal.ZERO, "PLATFORM");

        assertThat(c.discountFundedBy()).isNull();
        assertThat(c.discount()).isEqualByComparingTo("0");
    }

    /** The discount comes off the food; the fee and the tip are not discountable. */
    @Test
    void aDiscountReducesTheFoodButNotTheFeeOrTheTip() {
        BillingService.Charges c = serviceWith(settings(
                        BillingSettings.FEE_AMOUNT, "5", BillingSettings.FEE_CHARGE, "true",
                        BillingSettings.TIP_ENABLED, "true", BillingSettings.TIP_AMOUNTS, "10"))
                .charges(new BigDecimal("100.00"), outletAt(null), new BigDecimal("10"),
                        new BigDecimal("30.00"), "PLATFORM");

        // 100 - 30 + 5 + 10
        assertThat(c.total()).isEqualByComparingTo("85.00");
    }

    // ---- the platform fee and its free period -------------------------------

    @Test
    void aConfiguredFeeIsNotChargedUntilChargingIsSwitchedOn() {
        BillingService.Charges c = serviceWith(settings(BillingSettings.FEE_AMOUNT, "5"))
                .charges(new BigDecimal("100.00"), outletAt(null), null);

        assertThat(c.platformFee()).isEqualByComparingTo("0.00");
        // Still recorded, so the invoice can show it struck through.
        assertThat(c.platformFeeShown()).isEqualByComparingTo("5");
        assertThat(c.total()).isEqualByComparingTo("100.00");
    }

    @Test
    void switchingChargingOnAddsItToTheTotal() {
        BillingService.Charges c = serviceWith(settings(
                        BillingSettings.FEE_AMOUNT, "5", BillingSettings.FEE_CHARGE, "true"))
                .charges(new BigDecimal("100.00"), outletAt(null), null);

        assertThat(c.platformFee()).isEqualByComparingTo("5");
        assertThat(c.total()).isEqualByComparingTo("105.00");
    }

    @Test
    void theStrikethroughOnlyShowsWhileAPricedFeeIsNotBeingCharged() {
        BillingSettings free = BillingSettings.from(settings(
                BillingSettings.FEE_AMOUNT, "5", BillingSettings.FEE_SHOW_WHEN_FREE, "true"));
        assertThat(free.showFreeFee()).isTrue();

        BillingSettings charging = BillingSettings.from(settings(
                BillingSettings.FEE_AMOUNT, "5", BillingSettings.FEE_CHARGE, "true",
                BillingSettings.FEE_SHOW_WHEN_FREE, "true"));
        assertThat(charging.showFreeFee()).isFalse();

        BillingSettings noFee = BillingSettings.from(settings(BillingSettings.FEE_SHOW_WHEN_FREE, "true"));
        assertThat(noFee.showFreeFee()).isFalse();
    }

    // ---- the tip ------------------------------------------------------------

    /** The amounts are the operator's to set, so nothing else may be charged. */
    @Test
    void onlyAnOfferedTipAmountIsAccepted() {
        BillingService s = serviceWith(settings(
                BillingSettings.TIP_ENABLED, "true", BillingSettings.TIP_AMOUNTS, "5,10,20"));
        BillingSettings settings = s.settings();

        assertThat(s.acceptableTip(new BigDecimal("10"), settings)).isEqualByComparingTo("10");
        // A hand-edited form must not be able to name its own number.
        assertThat(s.acceptableTip(new BigDecimal("9999"), settings)).isEqualByComparingTo("0");
        assertThat(s.acceptableTip(new BigDecimal("7"), settings)).isEqualByComparingTo("0");
    }

    @Test
    void noTipIsTakenWhileTheDialogIsSwitchedOff() {
        BillingService s = serviceWith(settings(BillingSettings.TIP_AMOUNTS, "5,10"));
        assertThat(s.acceptableTip(new BigDecimal("10"), s.settings())).isEqualByComparingTo("0");
    }

    @Test
    void decliningAndNegativeAmountsBothMeanNothing() {
        BillingService s = serviceWith(settings(
                BillingSettings.TIP_ENABLED, "true", BillingSettings.TIP_AMOUNTS, "5"));
        BillingSettings settings = s.settings();

        assertThat(s.acceptableTip(null, settings)).isEqualByComparingTo("0");
        assertThat(s.acceptableTip(BigDecimal.ZERO, settings)).isEqualByComparingTo("0");
        assertThat(s.acceptableTip(new BigDecimal("-5"), settings)).isEqualByComparingTo("0");
    }

    // ---- settings parsing ---------------------------------------------------

    /** A malformed setting must never charge somebody an unpredictable amount. */
    @Test
    void anUnparseableAmountReadsAsZeroRatherThanFailing() {
        BillingSettings s = BillingSettings.from(settings(
                BillingSettings.FEE_AMOUNT, "not a number",
                BillingSettings.COMMISSION_PERCENT, ""));

        assertThat(s.platformFeeAmount()).isEqualByComparingTo("0");
        assertThat(s.commissionPercent()).isEqualByComparingTo("0");
    }

    @Test
    void oneBadTipAmountDoesNotEmptyTheWholeList() {
        BillingSettings s = BillingSettings.from(settings(BillingSettings.TIP_AMOUNTS, "5,oops,20,-3"));
        assertThat(s.tipAmounts()).containsExactly(new BigDecimal("5"), new BigDecimal("20"));
    }

    @Test
    void gstIsOnlySnapshotOntoAnOrderWhenTheBreakdownIsSwitchedOn() {
        BillingService.Charges off = serviceWith(settings(BillingSettings.GST_PERCENT, "5"))
                .charges(new BigDecimal("100.00"), outletAt(null), null);
        assertThat(off.gstPercent()).isEqualByComparingTo("0");

        BillingService.Charges on = serviceWith(settings(
                        BillingSettings.GST_PERCENT, "5", BillingSettings.GST_SHOW, "true"))
                .charges(new BigDecimal("100.00"), outletAt(null), null);
        assertThat(on.gstPercent()).isEqualByComparingTo("5");
    }
}
