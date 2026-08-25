package com.bitesite.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MenuItemTest {

    private MenuItem itemWith(BigDecimal price, BigDecimal discountPrice, BigDecimal discountPercent) {
        return MenuItem.builder()
                .id(1L).tenantId(1L).outletId(1L)
                .name("Samosa").category("Snacks")
                .price(price).discountPrice(discountPrice).discountPercent(discountPercent)
                .available(true)
                .build();
    }

    @Test
    void noDiscountMeansEffectivePriceEqualsPrice() {
        MenuItem item = itemWith(new BigDecimal("30.00"), null, null);
        assertThat(item.effectivePrice()).isEqualByComparingTo("30.00");
        assertThat(item.hasDiscount()).isFalse();
    }

    @Test
    void flatDiscountPriceWinsOverPercent() {
        MenuItem item = itemWith(new BigDecimal("30.00"), new BigDecimal("22.00"), new BigDecimal("50.00"));
        assertThat(item.effectivePrice()).isEqualByComparingTo("22.00");
        assertThat(item.hasDiscount()).isTrue();
    }

    @Test
    void percentDiscountAppliesWhenNoFlatPriceSet() {
        MenuItem item = itemWith(new BigDecimal("30.00"), null, new BigDecimal("10.00"));
        assertThat(item.effectivePrice()).isEqualByComparingTo("27.00");
        assertThat(item.hasDiscount()).isTrue();
    }

    @Test
    void zeroPercentDiscountIsTreatedAsNoDiscount() {
        MenuItem item = itemWith(new BigDecimal("30.00"), null, BigDecimal.ZERO);
        assertThat(item.effectivePrice()).isEqualByComparingTo("30.00");
        assertThat(item.hasDiscount()).isFalse();
    }

    private MenuItem named(String name, String category) {
        return MenuItem.builder()
                .id(1L).tenantId(1L).outletId(1L)
                .name(name).category(category)
                .price(new BigDecimal("30.00"))
                .available(true)
                .build();
    }

    @Test
    void illustrationMatchesTheItemsOwnNameBeforeItsCategory() {
        // "Cold Coffee" sits in a Beverages category — the name is the more specific
        // signal, so it must win over the generic drink illustration.
        assertThat(named("Cold Coffee", "Beverages").fallbackIllustration())
                .isEqualTo("/img/food/coffee.svg");
        assertThat(named("Masala Chai", "Beverages").fallbackIllustration())
                .isEqualTo("/img/food/chai.svg");
        assertThat(named("Samosa (2 pcs)", "Snacks").fallbackIllustration())
                .isEqualTo("/img/food/samosa.svg");
        assertThat(named("Chicken Roll", "Snacks").fallbackIllustration())
                .isEqualTo("/img/food/roll.svg");
        assertThat(named("Veg Sandwich", "Snacks").fallbackIllustration())
                .isEqualTo("/img/food/sandwich.svg");
    }

    @Test
    void moreSpecificKeywordsBeatOverlappingOnes() {
        // "ice cream" must not be swallowed by a looser match, and "cold coffee"
        // must not fall through to the generic drink glass.
        assertThat(named("Ice Cream Sundae", "Desserts").fallbackIllustration())
                .isEqualTo("/img/food/icecream.svg");
        assertThat(named("Veg Biryani", "Main Course").fallbackIllustration())
                .isEqualTo("/img/food/rice.svg");
        // Falls in a "Main Course" category, so without its own keyword it would have
        // silently rendered a rice bowl.
        assertThat(named("Masala Dosa", "Main Course").fallbackIllustration())
                .isEqualTo("/img/food/dosa.svg");
    }

    @Test
    void unrecognisedNameFallsBackToTheCategory() {
        assertThat(named("Butter Paneer", "Beverages").fallbackIllustration())
                .isEqualTo("/img/food/juice.svg");
        assertThat(named("Butter Paneer", "Main Course").fallbackIllustration())
                .isEqualTo("/img/food/rice.svg");
    }

    @Test
    void unrecognisedNameAndCategoryFallBackToThePlate() {
        assertThat(named("Butter Paneer", "Chef Special").fallbackIllustration())
                .isEqualTo("/img/food/plate.svg");
        assertThat(named(null, null).fallbackIllustration())
                .isEqualTo("/img/food/plate.svg");
    }

    @Test
    void aRealPhotoAlwaysWinsOverTheIllustration() {
        MenuItem item = named("Samosa", "Snacks");
        assertThat(item.usesIllustration()).isTrue();
        assertThat(item.displayImage()).isEqualTo("/img/food/food_puff.png");

        item.setPhotoPath("/uploads/menu-photos/real.jpg");
        assertThat(item.usesIllustration()).isFalse();
        assertThat(item.displayImage()).isEqualTo("/uploads/menu-photos/real.jpg");
    }

    @Test
    void detailedPngIllustrationsWinOverTheFlatSvgSet() {
        assertThat(named("Masala Dosa", "Main Course").displayImage()).isEqualTo("/img/food/food_dosa.png");
        assertThat(named("Masala Chai", "Beverages").displayImage()).isEqualTo("/img/food/food_chai.png");
        assertThat(named("Cold Coffee", "Beverages").displayImage()).isEqualTo("/img/food/food_shake.png");
        assertThat(named("Veg Maggi", "Snacks").displayImage()).isEqualTo("/img/food/food_maggi.png");
        assertThat(named("Aloo Paratha", "Meals").displayImage()).isEqualTo("/img/food/food_paratha.png");
        assertThat(named("Chicken Roll", "Snacks").displayImage()).isEqualTo("/img/food/food_wrap.png");
        assertThat(named("Veg Puff", "Snacks").displayImage()).isEqualTo("/img/food/food_puff.png");
        // The puff artwork shows a samosa alongside the puff, so samosas get it too.
        assertThat(named("Samosa (2 pcs)", "Snacks").displayImage()).isEqualTo("/img/food/food_puff.png");
        assertThat(named("Grilled Sandwich", "Snacks").displayImage()).isEqualTo("/img/food/food_sandwich.png");
        assertThat(named("White Sauce Pasta", "Meals").displayImage()).isEqualTo("/img/food/food_spaghetti.png");
        assertThat(named("Veg Burger", "Snacks").displayImage()).isEqualTo("/img/food/food_burger.png");
        assertThat(named("Chicken Curry", "Meals").displayImage()).isEqualTo("/img/food/food_chicken.png");
    }

    @Test
    void anItemWithNoPngMatchFallsThroughToTheSvgSet() {
        assertThat(named("Veg Thali", "Main Course").displayImage()).isEqualTo("/img/food/rice.svg");
        assertThat(named("Green Salad", "Sides").displayImage()).isEqualTo("/img/food/salad.svg");
    }

    @Test
    void keywordsMatchOnWordStartSoSubstringsDoNotFalseFire() {
        // "Steamed" contains "tea" (s-TEA-med). A plain contains() served a cup of chai
        // for steamed dishes, which is what this anchoring exists to prevent.
        assertThat(named("Steamed Rice", "Main Course").displayImage()).isEqualTo("/img/food/rice.svg");
        assertThat(named("Steamed Momos", "Snacks").displayImage()).isNotEqualTo("/img/food/food_chai.png");
        // ...while ordinary plurals still resolve.
        assertThat(named("Kathi Rolls", "Snacks").displayImage()).isEqualTo("/img/food/food_wrap.png");
        assertThat(named("Masala Fries", "Snacks").displayImage()).isEqualTo("/img/food/fries.svg");
    }

    @Test
    void everyIllustrationTheAppCanResolveExistsOnDisk() throws Exception {
        // The regression that motivated this: assets were renamed and deleted underneath
        // the resolver, and nothing failed because no test ever checked the file was
        // really there. Any menu item resolving to a missing asset is a broken image.
        String[] names = {
                "Masala Dosa", "Masala Chai", "Cold Coffee", "Veg Maggi", "Aloo Paratha",
                "Chicken Roll", "Veg Puff", "Grilled Sandwich", "White Sauce Pasta",
                "Veg Burger", "Chicken Curry", "Ramen Bowl", "Sushi Platter",
                "Veg Thali", "Green Salad", "Steamed Rice", "Masala Fries", "Gulab Jamun",
                "Ice Cream", "Margherita Pizza", "Fresh Juice", "Idli Sambar", "Mystery Special",
        };
        java.nio.file.Path staticRoot = java.nio.file.Paths.get("src/main/resources/static");
        for (String n : names) {
            for (String category : new String[]{"Snacks", "Beverages", "Main Course", "Desserts", "Chef Special"}) {
                String resolved = named(n, category).displayImage();
                java.nio.file.Path asset = staticRoot.resolve(resolved.substring(1));
                assertThat(java.nio.file.Files.exists(asset))
                        .as("asset %s for item '%s' (%s) must exist on disk", resolved, n, category)
                        .isTrue();
            }
        }
    }

    // ---------- Per-day capacity ----------

    private MenuItem capped(Integer dailyLimit, int soldToday, boolean available) {
        return MenuItem.builder()
                .id(1L).tenantId(1L).outletId(1L)
                .name("Masala Dosa").category("Meals")
                .price(new BigDecimal("60.00"))
                .available(available).dailyLimit(dailyLimit).soldToday(soldToday)
                .build();
    }

    @Test
    void anItemWithNoDailyLimitIsNeverSoldOutByCount() {
        MenuItem item = capped(null, 9999, true);
        assertThat(item.hasDailyLimit()).isFalse();
        assertThat(item.remainingToday()).isNull();
        assertThat(item.soldOutToday()).isFalse();
        assertThat(item.orderable()).isTrue();
    }

    @Test
    void remainingCountsDownAgainstTheDailyLimit() {
        assertThat(capped(30, 0, true).remainingToday()).isEqualTo(30);
        assertThat(capped(30, 28, true).remainingToday()).isEqualTo(2);
        assertThat(capped(30, 30, true).remainingToday()).isZero();
    }

    @Test
    void remainingNeverGoesNegativeWhenSimultaneousCheckoutsOvershootTheCap() {
        MenuItem item = capped(30, 32, true);
        assertThat(item.remainingToday()).isZero();
        assertThat(item.soldOutToday()).isTrue();
        assertThat(item.dailyLimitPercent()).isEqualTo(100);
    }

    @Test
    void orderableNeedsBothStaffAvailabilityAndRoomInTheDay() {
        assertThat(capped(30, 10, true).orderable()).isTrue();
        assertThat(capped(30, 30, true).orderable()).isFalse();  // cap reached
        assertThat(capped(30, 10, false).orderable()).isFalse(); // switched off by staff
        assertThat(capped(null, 0, false).orderable()).isFalse();
    }

    @Test
    void runningLowOnlyFiresInsideTheLastFewAndNotWhenSoldOut() {
        assertThat(capped(30, 24, true).runningLowToday()).isFalse(); // 6 left
        assertThat(capped(30, 25, true).runningLowToday()).isTrue();  // 5 left
        assertThat(capped(30, 30, true).runningLowToday()).isFalse(); // none left, that's "sold out"
        assertThat(capped(null, 0, true).runningLowToday()).isFalse();
    }

    @Test
    void dailyLimitPercentIsZeroWithoutACap() {
        assertThat(capped(null, 5, true).dailyLimitPercent()).isZero();
        assertThat(capped(40, 10, true).dailyLimitPercent()).isEqualTo(25);
    }
}
