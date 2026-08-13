package com.bitesite.dto;

/** A payment-intent created with the gateway, ready to hand to the client-side checkout widget. */
public record GatewayOrder(String gatewayOrderId, String keyId, long amountPaise, String currency) {
}
