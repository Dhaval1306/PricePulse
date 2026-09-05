package com.pricepulse.service;

public interface RateLimiterService {
    boolean tryAcquire(String domain);
}
