package com.bitesite.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MenuItem {
    private Long id;
    private Long tenantId;
    private Long outletId;
    private String name;
    private String category;
    private String photoPath;
    private BigDecimal price;
    private BigDecimal discountPrice;
    private BigDecimal discountPercent;
    private boolean available;

    /** Null means "no cap" — most items. A number is how many the kitchen can make in a day. */
    private Integer dailyLimit;

    /**
     * How many of this item today's orders have already committed to. Not a column: it is
     * rolled up from order_items at read time by {@link com.bitesite.service.MenuService},
     * so it resets by itself at midnight without a scheduled job to reset a counter — and
     * without a counter that could drift out of step with the orders it is supposed to count.
     */
    private int soldToday;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * A flat discount price always wins over a percentage discount when both are set,
     * since staff typically set one or the other, not both.
     */
    public BigDecimal effectivePrice() {
        if (discountPrice != null) {
            return discountPrice;
        }
        if (discountPercent != null && discountPercent.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal factor = BigDecimal.ONE.subtract(discountPercent.divide(BigDecimal.valueOf(100)));
            return price.multiply(factor).setScale(2, RoundingMode.HALF_UP);
        }
        return price;
    }

    public boolean hasDiscount() {
        return effectivePrice().compareTo(price) < 0;
    }

    public boolean hasDailyLimit() {
        return dailyLimit != null && dailyLimit > 0;
    }

    /** How many are still orderable today, or null when the item has no cap. */
    public Integer remainingToday() {
        return hasDailyLimit() ? Math.max(0, dailyLimit - soldToday) : null;
    }

    public boolean soldOutToday() {
        return hasDailyLimit() && soldToday >= dailyLimit;
    }

    /**
     * The single question every ordering surface actually asks. An item is orderable only
     * when staff have it switched on AND today's cap has room left — two separate reasons
     * to hide the Add button that would otherwise be re-combined by hand on each screen.
     */
    public boolean orderable() {
        return available && !soldOutToday();
    }

    /**
     * How full the day's cap is, 0-100, for the progress bar on the outlet's menu screen.
     * Clamped at 100 because a cap can be overshot slightly by simultaneous checkouts
     * (see OrderService.checkout), and a bar wider than its track is not the way to
     * report that.
     */
    public int dailyLimitPercent() {
        if (!hasDailyLimit()) {
            return 0;
        }
        return soldToday >= dailyLimit ? 100 : soldToday * 100 / dailyLimit;
    }

    /** True once the day's cap is close enough to nudge the student ("only 3 left"). */
    public boolean runningLowToday() {
        Integer remaining = remainingToday();
        return remaining != null && remaining > 0 && remaining <= 5;
    }

    /**
     * Detailed PNG illustrations, checked before the SVG set below. These are the
     * India-specific pieces (kulhad chai, masala dosa, paratha platter) and win when
     * an item name matches, because they read better than a generic drawn shape.
     */
    private static final String[][] PHOTO_KEYWORDS = {
            {"food_maggi", "maggi"},
            {"food_paratha", "paratha"},
            {"food_wrap", "roll", "wrap", "frankie"},
            {"food_puff", "puff", "patty", "samosa"},
            {"food_sandwich", "sandwich", "toast"},
            {"food_dosa", "dosa", "south"},
            {"food_chai", "chai", "tea"},
            {"food_shake", "shake", "boba", "cold coffee"},
            {"food_spaghetti", "pasta", "spaghetti"},
            {"food_ramen", "ramen"},
            {"food_burger", "burger"},
            {"food_chicken", "chicken"},
            {"food_sushi", "sushi"},
    };

    /**
     * Flat SVG illustrations covering everything the PNG set above doesn't. Order
     * matters: "cold coffee" must reach {@code coffee} before any generic drink rule,
     * and "ice cream" before anything matching "cream".
     */
    private static final String[][] ILLUSTRATION_KEYWORDS = {
            {"icecream", "ice cream", "icecream", "sundae", "kulfi", "gelato"},
            {"coffee", "coffee", "latte", "cappuccino", "espresso", "mocha"},
            {"chai", "chai", "tea"},
            {"juice", "juice", "shake", "smoothie", "lassi", "soda", "cola", "lemonade", "mojito", "drink"},
            {"pizza", "pizza", "garlic bread"},
            {"burger", "burger"},
            {"sandwich", "sandwich", "toast", "grilled cheese"},
            {"samosa", "samosa", "pakora", "vada", "kachori", "cutlet"},
            {"roll", "roll", "wrap", "frankie", "shawarma", "kathi", "paratha", "roti", "burrito"},
            {"noodles", "noodle", "maggi", "chowmein", "ramen", "pasta", "spaghetti", "macaroni"},
            {"dosa", "dosa", "uttapam", "idli", "appam", "vada pav"},
            {"rice", "rice", "biryani", "pulao", "khichdi", "thali"},
            {"fries", "fries", "chips", "wedges"},
            {"salad", "salad", "sprout"},
            {"sweet", "gulab", "jamun", "laddu", "halwa", "brownie", "cake", "pastry", "donut", "dessert", "sweet"},
    };

    /** Category → illustration, used when nothing in the item's name matches. */
    private static final String[][] CATEGORY_KEYWORDS = {
            {"juice", "beverage", "drink"},
            {"sweet", "dessert", "sweet"},
            {"samosa", "snack", "starter"},
            {"rice", "meal", "main", "lunch", "dinner"},
    };

    private static final String ILLUSTRATION_DIR = "/img/food/";
    private static final String ILLUSTRATION_FALLBACK = "plate";

    /**
     * Keyword match anchored to the start of a word. A plain {@code contains} is wrong
     * here: "Steamed Rice" contains "tea" (s-TEA-med) and would have rendered a cup of
     * chai. Only the left edge is anchored, so ordinary plurals — "Kathi Rolls" matching
     * "roll" — still work.
     */
    private static boolean mentions(String haystack, String needle) {
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            if (i == 0 || !Character.isLetterOrDigit(haystack.charAt(i - 1))) {
                return true;
            }
            i = haystack.indexOf(needle, i + 1);
        }
        return false;
    }

    private static String matchKeywords(String haystack, String[][] table, String extension) {
        for (String[] row : table) {
            for (int i = 1; i < row.length; i++) {
                if (mentions(haystack, row[i])) {
                    return ILLUSTRATION_DIR + row[0] + extension;
                }
            }
        }
        return null;
    }

    /**
     * Flat SVG standing in for a missing photo when no detailed PNG matches. Canteens
     * rarely upload a photo for every item, and rendering one identical grey placeholder
     * down the whole menu is what made the list read as a data table rather than food.
     */
    public String fallbackIllustration() {
        String byName = matchKeywords(name == null ? "" : name.toLowerCase(), ILLUSTRATION_KEYWORDS, ".svg");
        if (byName != null) {
            return byName;
        }
        String byCategory = matchKeywords(category == null ? "" : category.toLowerCase(), CATEGORY_KEYWORDS, ".svg");
        return byCategory != null ? byCategory : ILLUSTRATION_DIR + ILLUSTRATION_FALLBACK + ".svg";
    }

    /**
     * What to actually render for this item: the canteen's own photo when there is one,
     * then a detailed PNG illustration, then a flat SVG. Templates call this instead of
     * branching on photoPath, so the choice lives in one place rather than per screen.
     */
    public String displayImage() {
        if (photoPath != null) {
            return photoPath;
        }
        String png = matchKeywords(name == null ? "" : name.toLowerCase(), PHOTO_KEYWORDS, ".png");
        return png != null ? png : fallbackIllustration();
    }

    /** True when {@link #displayImage()} is an illustration rather than a real photo. */
    public boolean usesIllustration() {
        return photoPath == null;
    }

    /**
     * Whole-percent discount for display (e.g. menu badges), regardless of whether staff
     * set a flat discount price or a percent — computed from the actual price difference
     * so it's always correct even when a flat price was used.
     */
    public Integer discountPercentDisplay() {
        if (!hasDiscount()) {
            return null;
        }
        if (discountPercent != null && discountPercent.compareTo(BigDecimal.ZERO) > 0) {
            return discountPercent.setScale(0, RoundingMode.HALF_UP).intValue();
        }
        return price.subtract(effectivePrice())
                .multiply(BigDecimal.valueOf(100))
                .divide(price, 0, RoundingMode.HALF_UP)
                .intValue();
    }
}
