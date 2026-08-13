package com.bitesite.dto;

import com.bitesite.model.Order;

public record CheckoutResult(Order order, GatewayOrder gatewayOrder) {
}
