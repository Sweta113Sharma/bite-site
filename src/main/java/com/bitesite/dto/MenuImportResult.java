package com.bitesite.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What an import would do, or did. The same object serves the preview and the applied
 * run, so the screen an admin confirms from is rendered by the same code that reports the
 * outcome — a preview that could disagree with the result would be worse than none.
 */
public class MenuImportResult {

    /** One problem, tied to the line of the file it came from. */
    public record Problem(int line, String message) {}

    /** One item the file asks for, and what would become of it. */
    public record Row(int line, String category, String name, BigDecimal price,
                      BigDecimal discountPrice, BigDecimal discountPercent, Integer dailyLimit,
                      boolean isNew, BigDecimal previousPrice) {

        /** True when this row changes the price of an item that already exists. */
        public boolean isPriceChange() {
            return !isNew && previousPrice != null && previousPrice.compareTo(price) != 0;
        }
    }

    private final List<Problem> problems = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final Set<String> newCategories = new LinkedHashSet<>();
    private boolean applied;

    public void addProblem(int line, String message) {
        problems.add(new Problem(line, message));
    }

    public void addRow(Row row) {
        rows.add(row);
    }

    public void addNewCategory(String name) {
        newCategories.add(name);
    }

    public void markApplied() {
        this.applied = true;
    }

    public List<Problem> getProblems() {
        return problems;
    }

    public List<Row> getRows() {
        return rows;
    }

    public Set<String> getNewCategories() {
        return newCategories;
    }

    public boolean isApplied() {
        return applied;
    }

    public boolean hasProblems() {
        return !problems.isEmpty();
    }

    public long getCreateCount() {
        return rows.stream().filter(Row::isNew).count();
    }

    public long getUpdateCount() {
        return rows.stream().filter(r -> !r.isNew()).count();
    }

    public long getPriceChangeCount() {
        return rows.stream().filter(Row::isPriceChange).count();
    }
}
