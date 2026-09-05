package com.pricepulse.service;

public interface RateLimiterService {

    /**
     * Checks if a request is permitted within the distributed sliding window log for the given platform.
     * Uses atomic Redis Sorted Sets via an atomic Lua script.
     *
     * @param platform domain/platform name (e.g. "amazon", "flipkart")
     * @return true if allowed within quota, false if throttled
     */
    boolean tryAcquire(String platform);

    /**
     * Returns the remaining request quota available in the current rolling window.
     */
    long getRemainingQuota(String platform);
}
