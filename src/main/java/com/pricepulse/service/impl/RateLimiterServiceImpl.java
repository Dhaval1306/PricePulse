package com.pricepulse.service.impl;

import com.pricepulse.service.RateLimiterService;
import org.springframework.stereotype.Service;

@Service
public class RateLimiterServiceImpl implements RateLimiterService {

    @Override
    public boolean tryAcquire(String domain) {
        // To be implemented in Phase 2 Module 4
        return true;
    }
}
