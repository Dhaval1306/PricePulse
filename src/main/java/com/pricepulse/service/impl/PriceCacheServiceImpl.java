package com.pricepulse.service.impl;

import com.pricepulse.service.PriceCacheService;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.Optional;

@Service
public class PriceCacheServiceImpl implements PriceCacheService {

    @Override
    public boolean hasPriceChanged(Long productId, BigDecimal newPrice) {
        // To be implemented in Phase 2 Module 1
        return true;
    }

    @Override
    public void updateCachedPrice(Long productId, BigDecimal newPrice) {
        // To be implemented in Phase 2 Module 1
    }

    @Override
    public Optional<BigDecimal> getCachedPrice(Long productId) {
        // To be implemented in Phase 2 Module 1
        return Optional.empty();
    }

    @Override
    public void evictCachedPrice(Long productId) {
        // To be implemented in Phase 2 Module 1
    }
}
