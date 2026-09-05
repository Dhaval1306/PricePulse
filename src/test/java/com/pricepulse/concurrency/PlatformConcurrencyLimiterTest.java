package com.pricepulse.concurrency;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlatformConcurrencyLimiterTest {

    private PlatformConcurrencyLimiter limiter;

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        limiter = new PlatformConcurrencyLimiter(registry);
        // Configure 2 permits for amazon in test
        ReflectionTestUtils.setField(limiter, "amazonPermits", 2);
    }

    @Test
    @DisplayName("Should permit up to configured limit and block/timeout subsequent threads")
    void testConcurrencyBoundEnforced() throws Exception {
        // First 2 acquires should succeed immediately
        assertTrue(limiter.tryAcquire("amazon", Duration.ofMillis(500)));
        assertTrue(limiter.tryAcquire("amazon", Duration.ofMillis(500)));

        // 3rd acquire must timeout because permits are exhausted
        assertFalse(limiter.tryAcquire("amazon", Duration.ofMillis(300)),
                "3rd concurrent acquire must timeout when permit limit is 2");

        // Releasing 1 permit should allow the next acquire to succeed
        limiter.release("amazon");
        assertTrue(limiter.tryAcquire("amazon", Duration.ofMillis(500)),
                "Acquire should succeed after a permit was released");

        // Cleanup
        limiter.release("amazon");
        limiter.release("amazon");
    }

    @Test
    @DisplayName("Should coordinate permits across multiple concurrent threads")
    void testConcurrentThreadsAcquisition() throws Exception {
        int threadsCount = 5;
        CountDownLatch latch = new CountDownLatch(threadsCount);
        AtomicInteger successfulAcquisitions = new AtomicInteger(0);

        for (int i = 0; i < threadsCount; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    if (limiter.tryAcquire("amazon", Duration.ofMillis(100))) {
                        successfulAcquisitions.incrementAndGet();
                        // Hold permit briefly
                        Thread.sleep(500);
                        limiter.release("amazon");
                    }
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(3, TimeUnit.SECONDS);
        // Only 2 threads should have acquired permits within the short 100ms window
        assertEquals(2, successfulAcquisitions.get(),
                "Only 2 threads should be permitted concurrently when limit is 2");
    }

    @Test
    @DisplayName("Should eagerly report configured permits for both platforms even before any scrape has occurred")
    void testEagerPermitAvailabilityBeforeScraping() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlatformConcurrencyLimiter freshLimiter = new PlatformConcurrencyLimiter(registry);
        ReflectionTestUtils.setField(freshLimiter, "amazonPermits", 3);
        ReflectionTestUtils.setField(freshLimiter, "flipkartPermits", 3);
        freshLimiter.init();

        assertEquals(3, freshLimiter.getAvailablePermits("amazon"),
                "Amazon permits should report configured max (3) before any scrape");
        assertEquals(3, freshLimiter.getAvailablePermits("flipkart"),
                "Flipkart permits should report configured max (3) even when no Flipkart URL has been scraped");
    }
}
