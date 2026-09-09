package com.bitesite.service;

import com.bitesite.dto.MenuImportResult;
import com.bitesite.dto.MenuItemForm;
import com.bitesite.exception.BusinessException;
import com.bitesite.model.Category;
import com.bitesite.model.MenuItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds a canteen's menu from a spreadsheet, so onboarding is one upload rather than an
 * evening of typing. The outlet then adds photos by editing items normally — the import
 * deliberately has nothing to say about images.
 *
 * <p><b>Upsert, never delete.</b> Items are matched to existing ones by name within the
 * outlet, so re-uploading a corrected file fixes prices instead of producing a second copy
 * of the menu. Nothing is ever removed: a file that omits an item leaves that item alone,
 * because "this spreadsheet is now the whole truth" is a much more dangerous promise than
 * "these are the items I am setting", and an admin who wants an item gone can delete it.
 *
 * <p><b>Photos survive.</b> An update goes through {@link MenuService#update} with a null
 * photo and removePhoto false, which keeps whatever the outlet uploaded. That is the whole
 * reason the two halves of this workflow can coexist: prices come from the sheet,
 * pictures come from the people holding the food.
 *
 * <p><b>Validation is per line and complete.</b> The file is checked in full before
 * anything is written, and every problem is reported with its line number, so an admin
 * fixes all of them in one pass rather than rediscovering the next one on each attempt.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MenuImportService {

    /** Long enough for any real canteen, short enough that a wrong file is not a wrecking ball. */
    private static final int MAX_ROWS = 500;

    /** The column widths in the schema; exceeded, MySQL truncates or throws. */
    private static final int MAX_NAME = 150;
    private static final int MAX_CATEGORY = 80;

    /** decimal(10,2) — and a menu item priced above this is a typo, not a menu item. */
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999.99");

    private static final String COL_CATEGORY = "category";
    private static final String COL_ITEM = "item";
    private static final String COL_PRICE = "price";
    private static final String COL_DISCOUNT_PRICE = "discount_price";
    private static final String COL_DISCOUNT_PERCENT = "discount_percent";
    private static final String COL_DAILY_LIMIT = "daily_limit";

    private final MenuService menuService;
    private final CategoryService categoryService;

    /** The file every admin should start from, so the format documents itself. */
    public static String templateCsv() {
        return """
                category,item,price,discount_price,discount_percent,daily_limit
                Snacks,Samosa,20.00,,,
                Snacks,"Chicken Roll, Large",90.00,75.00,,30
                Beverages,Masala Chai,15.00,,10,
                Beverages,Cold Coffee,60.00,,,20
                """;
    }

    /**
     * Reads and checks the file without touching anything.
     *
     * <p>Always run before {@link #apply}, and offered to the admin as the default, because
     * a spreadsheet is the easiest thing in the world to get one column wrong in and this
     * writes to a live menu students are ordering from.
     */
    public MenuImportResult preview(MultipartFile file, Long outletId, Long tenantId) {
        MenuImportResult result = new MenuImportResult();
        List<List<String>> rows = read(file);
        if (rows.isEmpty()) {
            result.addProblem(0, "That file has no rows in it.");
            return result;
        }

        Map<String, Integer> columns = headerIndex(rows.get(0), result);
        if (result.hasProblems()) {
            return result;
        }

        // What exists now, matched case-insensitively so "Snacks" does not become a second
        // section beside "snacks".
        Map<String, MenuItem> existingItems = new HashMap<>();
        for (MenuItem item : menuService.listForOutlet(outletId, tenantId)) {
            existingItems.put(key(item.getName()), item);
        }
        Map<String, Category> existingCategories = new HashMap<>();
        for (Category category : categoryService.listForOutlet(outletId, tenantId)) {
            existingCategories.put(key(category.getName()), category);
        }

        Map<String, Integer> seenInFile = new LinkedHashMap<>();
        int dataRows = rows.size() - 1;
        if (dataRows > MAX_ROWS) {
            result.addProblem(0, "That file has " + dataRows + " rows; the limit is " + MAX_ROWS + ".");
            return result;
        }

        for (int i = 1; i < rows.size(); i++) {
            // +1 again because a spreadsheet's first line is line 1, and the admin is
            // going to be looking at it in a spreadsheet.
            int line = i + 1;
            List<String> row = rows.get(i);

            String category = value(row, columns, COL_CATEGORY);
            String name = value(row, columns, COL_ITEM);
            String priceText = value(row, columns, COL_PRICE);

            if (category.isEmpty() || name.isEmpty() || priceText.isEmpty()) {
                result.addProblem(line, "Needs a category, an item and a price.");
                continue;
            }
            if (name.length() > MAX_NAME) {
                result.addProblem(line, "Item name is " + name.length() + " characters; the limit is " + MAX_NAME + ".");
                continue;
            }
            if (category.length() > MAX_CATEGORY) {
                result.addProblem(line, "Category is " + category.length() + " characters; the limit is " + MAX_CATEGORY + ".");
                continue;
            }
            Integer firstSeen = seenInFile.get(key(name));
            if (firstSeen != null) {
                result.addProblem(line, "\"" + name + "\" is already on line " + firstSeen
                        + " of this file; the later one would silently win.");
                continue;
            }

            BigDecimal price = money(priceText, result, line, "Price");
            if (price == null) {
                continue;
            }
            BigDecimal discountPrice = null;
            String discountPriceText = value(row, columns, COL_DISCOUNT_PRICE);
            if (!discountPriceText.isEmpty()) {
                discountPrice = money(discountPriceText, result, line, "Discount price");
                if (discountPrice == null) {
                    continue;
                }
                if (discountPrice.compareTo(price) >= 0) {
                    result.addProblem(line, "Discount price " + discountPrice + " is not below the price " + price + ".");
                    continue;
                }
            }
            BigDecimal discountPercent = null;
            String discountPercentText = value(row, columns, COL_DISCOUNT_PERCENT);
            if (!discountPercentText.isEmpty()) {
                discountPercent = money(discountPercentText, result, line, "Discount percent");
                if (discountPercent == null) {
                    continue;
                }
                if (discountPercent.compareTo(BigDecimal.ZERO) <= 0
                        || discountPercent.compareTo(new BigDecimal("100")) >= 0) {
                    result.addProblem(line, "Discount percent must be between 0 and 100.");
                    continue;
                }
            }
            if (discountPrice != null && discountPercent != null) {
                result.addProblem(line, "Set a discount price or a discount percent, not both.");
                continue;
            }
            Integer dailyLimit = null;
            String dailyLimitText = value(row, columns, COL_DAILY_LIMIT);
            if (!dailyLimitText.isEmpty()) {
                try {
                    dailyLimit = Integer.valueOf(dailyLimitText);
                } catch (NumberFormatException e) {
                    result.addProblem(line, "Daily limit \"" + dailyLimitText + "\" is not a whole number.");
                    continue;
                }
                if (dailyLimit <= 0) {
                    result.addProblem(line, "Daily limit must be more than zero, or blank for no limit.");
                    continue;
                }
            }

            seenInFile.put(key(name), line);
            MenuItem existing = existingItems.get(key(name));
            if (!existingCategories.containsKey(key(category))) {
                result.addNewCategory(category);
            }
            result.addRow(new MenuImportResult.Row(line, category, name, price, discountPrice,
                    discountPercent, dailyLimit, existing == null, existing == null ? null : existing.getPrice()));
        }
        return result;
    }

    /**
     * Applies a file that has already been checked.
     *
     * <p>Transactional, so a menu is never half-imported: an admin who hits a problem gets
     * the menu they had, not a section and a half of a new one. It re-runs the whole
     * validation rather than trusting a preview, because the two calls are separate
     * requests and the file could differ between them.
     */
    @Transactional
    public MenuImportResult apply(MultipartFile file, Long outletId, Long tenantId, Long actorUserId) {
        MenuImportResult result = preview(file, outletId, tenantId);
        if (result.hasProblems() || result.getRows().isEmpty()) {
            return result;
        }

        Map<String, Category> categories = new HashMap<>();
        for (Category category : categoryService.listForOutlet(outletId, tenantId)) {
            categories.put(key(category.getName()), category);
        }
        Map<String, MenuItem> items = new HashMap<>();
        for (MenuItem item : menuService.listForOutlet(outletId, tenantId)) {
            items.put(key(item.getName()), item);
        }

        for (MenuImportResult.Row row : result.getRows()) {
            Category category = categories.computeIfAbsent(key(row.category()),
                    k -> categoryService.create(tenantId, outletId, row.category(), actorUserId));

            MenuItemForm form = new MenuItemForm();
            form.setName(row.name());
            form.setCategoryId(category.getId());
            form.setPrice(row.price());
            form.setDiscountPrice(row.discountPrice());
            form.setDiscountPercent(row.discountPercent());
            form.setDailyLimit(row.dailyLimit());

            MenuItem existing = items.get(key(row.name()));
            if (existing == null) {
                menuService.create(tenantId, outletId, form, null, actorUserId);
            } else {
                // Null photo and removePhoto left false: whatever picture the outlet
                // uploaded stays exactly where it is. See MenuService.resolvePhotoPath.
                menuService.update(existing.getId(), tenantId, form, null, actorUserId);
            }
        }

        result.markApplied();
        log.info("Menu import applied to outlet {} (tenant {}) by user {}: {} created, {} updated, {} new categories",
                outletId, tenantId, actorUserId, result.getCreateCount(), result.getUpdateCount(),
                result.getNewCategories().size());
        return result;
    }

    private List<List<String>> read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Choose a CSV file to upload.");
        }
        try {
            return CsvReader.parse(new String(file.getBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new BusinessException("That file could not be read.");
        }
    }

    /**
     * Maps the header names to their positions, so the columns can be in any order and
     * the optional ones can simply be absent — an admin should not have to keep empty
     * columns around to satisfy a parser.
     */
    private Map<String, Integer> headerIndex(List<String> header, MenuImportResult result) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            columns.put(header.get(i).trim().toLowerCase(Locale.ROOT).replace(' ', '_'), i);
        }
        List<String> missing = new ArrayList<>();
        for (String required : List.of(COL_CATEGORY, COL_ITEM, COL_PRICE)) {
            if (!columns.containsKey(required)) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            result.addProblem(1, "The header row is missing: " + String.join(", ", missing)
                    + ". Expected at least category, item and price.");
        }
        return columns;
    }

    private String value(List<String> row, Map<String, Integer> columns, String column) {
        Integer index = columns.get(column);
        // A short row is normal: spreadsheets stop writing commas once the rest is empty.
        if (index == null || index >= row.size()) {
            return "";
        }
        return row.get(index).trim();
    }

    /** Money, or null with the problem recorded. Strips a currency symbol and separators,
     *  because people paste those in and refusing them teaches nobody anything. */
    private BigDecimal money(String text, MenuImportResult result, int line, String label) {
        String cleaned = text.replace("₹", "").replace("Rs.", "").replace("Rs", "")
                .replace(",", "").trim();
        BigDecimal value;
        try {
            value = new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            result.addProblem(line, label + " \"" + text + "\" is not a number.");
            return null;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            result.addProblem(line, label + " cannot be negative.");
            return null;
        }
        if (value.compareTo(MAX_PRICE) > 0) {
            result.addProblem(line, label + " " + value + " is higher than this system allows (" + MAX_PRICE + ").");
            return null;
        }
        return value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** Matching is case- and space-insensitive, so "Cold Coffee" and "cold coffee" are one item. */
    private String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
