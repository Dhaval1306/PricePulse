package com.pricepulse.service;

import com.pricepulse.dto.PriceDeltaResult;
import com.pricepulse.dto.ProductCacheState;
import java.math.BigDecimal;
import java.util.Optional;

public interface PriceCacheService {

    /**
     * Attempts to acquire a distributed lock on a product using Redis SETNX.
     * Prevents race conditions from concurrent scrapes of the same product.
     *
     * @param productId product identifier
     * @param timeoutSeconds lock expiration timeout to avoid deadlocks
     * @return Optional containing lockToken if acquired, empty if contested
     */
    Optional<String> acquireLock(Long productId, long timeoutSeconds);

    /**
     * Releases the distributed lock using atomic Lua script verification
     * to ensure only the lock owner can release it.
     *
     * @param productId product identifier
     * @param lockToken unique token returned from acquireLock
     */
    void releaseLock(Long productId, String lockToken);

    /**
     * Compares the scraped price/stock against the cached state.
     * - Resets Redis TTL on EVERY touch (both unchanged and changed cases).
     * - If unchanged: avoids DB write, updates last-checked timestamp, increments writes.avoided metric.
     * - If changed: updates PostgreSQL (product + price_history), updates Redis, increments writes.committed metric.
     * - If cache miss: falls back to PostgreSQL, seeds Redis, and performs comparison.
     *
     * @param productId product identifier
     * @param scrapedPrice newly scraped price
     * @param inStock current stock status
     * @return PriceDeltaResult containing comparison details and whether a DB write was avoided
     */
    PriceDeltaResult processPriceUpdate(Long productId, BigDecimal scrapedPrice, boolean inStock);

    /**
     * Retrieves current cached state if present.
     */
    Optional<ProductCacheState> getCachedState(Long productId);

    /**
     * Explicitly evicts cached state for a product.
     */
    void evictCachedState(Long productId);
}
