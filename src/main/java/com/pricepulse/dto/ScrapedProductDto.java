package com.pricepulse.dto;

import java.math.BigDecimal;

public record ScrapedProductDto(
    String title,
    BigDecimal price,
    String currency,
    boolean isInStock,
    String imageUrl,
    String platform
) {}
