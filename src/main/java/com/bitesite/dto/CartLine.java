package com.bitesite.dto;

import com.bitesite.model.MenuItem;

import java.math.BigDecimal;

public record CartLine(MenuItem item, int quantity, BigDecimal lineTotal) {
}
