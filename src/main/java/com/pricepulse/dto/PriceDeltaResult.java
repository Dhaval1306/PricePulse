package com.pricepulse.dto;

import java.math.BigDecimal;

public record PriceDeltaResult(
    boolean priceChanged,
    boolean stockChanged,
    BigDecimal oldPrice,
    BigDecimal newPrice,
    boolean writeAvoided
) {}
