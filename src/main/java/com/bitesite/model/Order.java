package com.bitesite.model;

import java.sql.Timestamp;
import java.util.List;

public class Order {
    private int orderId;
    private int userId;
    private String tokenNo;
    private double totalAmount;
    private String status; // PLACED, PREPARING, READY, COMPLETED, CANCELLED
    private Timestamp createdAt;
    private List<OrderItem> items;

    public Order() {}

    public Order(int userId, String tokenNo, double totalAmount, String status, List<OrderItem> items) {
        this.userId = userId;
        this.tokenNo = tokenNo;
        this.totalAmount = totalAmount;
        this.status = status;
        this.items = items;
    }

    public int getOrderId() { return orderId; }
    public void setOrderId(int orderId) { this.orderId = orderId; }

    public int getUserId() { return userId; }
    public void setUserId(int userId) { this.userId = userId; }

    public String getTokenNo() { return tokenNo; }
    public void setTokenNo(String tokenNo) { this.tokenNo = tokenNo; }

    public double getTotalAmount() { return totalAmount; }
    public void setTotalAmount(double totalAmount) { this.totalAmount = totalAmount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }

    public List<OrderItem> getItems() { return items; }
    public void setItems(List<OrderItem> items) { this.items = items; }
}