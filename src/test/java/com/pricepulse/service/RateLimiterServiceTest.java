package com.pricepulse.service;

import com.github.fppt.jedismock.RedisServer;
import com.pricepulse.service.impl.RateLimiterServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterServiceTest {

    private RedisServer redisServer;
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RateLimiterServiceImpl rateLimiterService;

    @BeforeEach
    void setUp() throws IOException {
        // Start in-memory mock Redis server
        redisServer = RedisServer.newRedisServer().start();

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                redisServer.getHost(),
                redisServer.getBindPort()
        );
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        rateLimiterService = new RateLimiterServiceImpl(redisTemplate, meterRegistry);

        // Configure a tight quota of 3 requests per 5000ms window for testing
        ReflectionTestUtils.setField(rateLimiterService, "amazonMaxRequests", 3);
        ReflectionTestUtils.setField(rateLimiterService, "amazonWindowMs", 5000L);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (redisServer != null) {
            redisServer.stop();
        }
    }

    @Test
    @DisplayName("Should allow requests up to max quota and throttle when quota exceeded")
    void testSlidingWindowQuotaEnforced() {
        // First 3 requests should be permitted
        assertTrue(rateLimiterService.tryAcquire("amazon"), "Request 1 should be allowed");
        assertTrue(rateLimiterService.tryAcquire("amazon"), "Request 2 should be allowed");
        assertTrue(rateLimiterService.tryAcquire("amazon"), "Request 3 should be allowed");

        // 4th request in the same window must be throttled
        assertFalse(rateLimiterService.tryAcquire("amazon"),
                "Request 4 must be throttled (quota was 3)");
    }
}
