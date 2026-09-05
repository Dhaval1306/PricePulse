package com.pricepulse.service.impl;

import com.pricepulse.service.RateLimiterService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class RateLimiterServiceImpl implements RateLimiterService {

    private static final Logger logger = LoggerFactory.getLogger(RateLimiterServiceImpl.class);

    private static final String RATE_LIMIT_KEY_PREFIX = "pricepulse:ratelimit:";

    // Atomic Redis Lua script implementing the Sliding Window Log algorithm
    private static final String SLIDING_WINDOW_LUA_SCRIPT =
            "local key = KEYS[1] " +
            "local now = tonumber(ARGV[1]) " +
            "local window = tonumber(ARGV[2]) " +
            "local max_requests = tonumber(ARGV[3]) " +
            "local member = ARGV[4] " +
            "local ttl = tonumber(ARGV[5]) " +
            "local clear_before = now - window " +
            "redis.call('ZREMRANGEBYSCORE', key, 0, clear_before) " +
            "local current_requests = redis.call('ZCARD', key) " +
            "if current_requests < max_requests then " +
            "    redis.call('ZADD', key, now, member) " +
            "    redis.call('EXPIRE', key, ttl) " +
            "    return 1 " +
            "else " +
            "    return 0 " +
            "end";

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${scraper.ratelimit.amazon.max-requests:20}")
    private int amazonMaxRequests;

    @Value("${scraper.ratelimit.amazon.window-ms:60000}")
    private long amazonWindowMs;

    @Value("${scraper.ratelimit.flipkart.max-requests:20}")
    private int flipkartMaxRequests;

    @Value("${scraper.ratelimit.flipkart.window-ms:60000}")
    private long flipkartWindowMs;

    @Value("${scraper.ratelimit.default.max-requests:30}")
    private int defaultMaxRequests;

    @Value("${scraper.ratelimit.default.window-ms:60000}")
    private long defaultWindowMs;

    public RateLimiterServiceImpl(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean tryAcquire(String platform) {
        String normalizedPlatform = normalize(platform);
        String key = RATE_LIMIT_KEY_PREFIX + normalizedPlatform;
        long now = System.currentTimeMillis();
        long windowMs = resolveWindowMs(normalizedPlatform);
        int maxRequests = resolveMaxRequests(normalizedPlatform);
        String member = UUID.randomUUID().toString();
        long ttlSeconds = Math.max(120, (windowMs / 1000) * 2);

        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(SLIDING_WINDOW_LUA_SCRIPT, Long.class);
            List<String> keys = Collections.singletonList(key);
            Long result = redisTemplate.execute(
                    script,
                    keys,
                    String.valueOf(now),
                    String.valueOf(windowMs),
                    String.valueOf(maxRequests),
                    member,
                    String.valueOf(ttlSeconds)
            );

            boolean allowed = (result != null && result == 1L);
            if (allowed) {
                meterRegistry.counter("pricepulse.ratelimiter.allowed", "platform", normalizedPlatform).increment();
                logger.debug("Rate limiter [ALLOWED]: Platform '{}' under quota limit of {}/{}ms",
                        normalizedPlatform, maxRequests, windowMs);
            } else {
                meterRegistry.counter("pricepulse.ratelimiter.throttled", "platform", normalizedPlatform).increment();
                logger.warn("Rate limiter [THROTTLED]: Platform '{}' reached quota of {} requests per {}ms",
                        normalizedPlatform, maxRequests, windowMs);
            }
            return allowed;

        } catch (Exception e) {
            logger.error("Redis failure during rate limit check for '{}': {}. Defaulting to fail-open.",
                    normalizedPlatform, e.getMessage());
            // Fail-open so monitoring does not halt if Redis has transient network blip
            return true;
        }
    }

    @Override
    public long getRemainingQuota(String platform) {
        String normalizedPlatform = normalize(platform);
        String key = RATE_LIMIT_KEY_PREFIX + normalizedPlatform;
        long now = System.currentTimeMillis();
        long windowMs = resolveWindowMs(normalizedPlatform);
        int maxRequests = resolveMaxRequests(normalizedPlatform);

        try {
            redisTemplate.opsForZSet().removeRangeByScore(key, 0, now - windowMs);
            Long current = redisTemplate.opsForZSet().zCard(key);
            long used = (current != null) ? current : 0L;
            return Math.max(0, maxRequests - used);
        } catch (Exception e) {
            return maxRequests;
        }
    }

    private int resolveMaxRequests(String platform) {
        return switch (platform) {
            case "amazon" -> amazonMaxRequests;
            case "flipkart" -> flipkartMaxRequests;
            default -> defaultMaxRequests;
        };
    }

    private long resolveWindowMs(String platform) {
        return switch (platform) {
            case "amazon" -> amazonWindowMs;
            case "flipkart" -> flipkartWindowMs;
            default -> defaultWindowMs;
        };
    }

    private String normalize(String platform) {
        if (platform == null) return "default";
        String lower = platform.toLowerCase().trim();
        if (lower.contains("amazon")) return "amazon";
        if (lower.contains("flipkart")) return "flipkart";
        return "default";
    }
}
