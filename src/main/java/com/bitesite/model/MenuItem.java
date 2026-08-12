package com.bitesite.model;

public class MenuItem {
    private int itemId;
    private String itemName;
    private String category;
    private double price;
    private boolean isAvailable;

    public MenuItem() {}

    public MenuItem(int itemId, String itemName, String category, double price, boolean isAvailable) {
        this.itemId = itemId;
        this.itemName = itemName;
        this.category = category;
        this.price = price;
        this.isAvailable = isAvailable;
    }

    public int getItemId() { return itemId; }
    public void setItemId(int itemId) { this.itemId = itemId; }

    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = price; }

    public boolean isAvailable() { return isAvailable; }
    public void setAvailable(boolean isAvailable) { this.isAvailable = isAvailable; }
}