package com.bitesite.model;

/**
 * Why the kitchen took items off a paid order. A fixed list rather than free text, because
 * one of them has a side effect: "out of stock" also takes the item off sale for the rest
 * of the day, and that must not hinge on how somebody happened to spell it.
 */
public enum ItemCancelReason {
    OUT_OF_STOCK("Out of stock", true),
    CANNOT_MAKE("Can't be made right now", false),
    STUDENT_REQUEST("Removed at your request", false);

    private final String label;
    private final boolean marksOutOfStock;

    ItemCancelReason(String label, boolean marksOutOfStock) {
        this.label = label;
        this.marksOutOfStock = marksOutOfStock;
    }

    /** The words the student is shown next to the removed line. */
    public String label() {
        return label;
    }

    public boolean marksOutOfStock() {
        return marksOutOfStock;
    }
}
