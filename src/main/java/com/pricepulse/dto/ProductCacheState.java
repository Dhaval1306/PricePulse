package com.pricepulse.dto;

import java.math.BigDecimal;

public record ProductCacheState(
    BigDecimal price,
    boolean inStock,
    long lastCheckedEpochMs
) {}
