package com.pricepulse.service;

import java.math.BigDecimal;
import java.util.Optional;

public interface PriceCacheService {
    boolean hasPriceChanged(Long productId, BigDecimal newPrice);
    void updateCachedPrice(Long productId, BigDecimal newPrice);
    Optional<BigDecimal> getCachedPrice(Long productId);
    void evictCachedPrice(Long productId);
}
