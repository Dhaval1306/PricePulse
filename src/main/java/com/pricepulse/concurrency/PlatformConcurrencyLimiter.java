package com.pricepulse.concurrency;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class PlatformConcurrencyLimiter {

    private static final Logger logger = LoggerFactory.getLogger(PlatformConcurrencyLimiter.class);

    // Explicitly use ReentrantLock instead of synchronized blocks to prevent virtual thread carrier pinning
    private final ReentrantLock registryLock = new ReentrantLock();

    private final ConcurrentHashMap<String, Semaphore> semaphores = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;

    @Value("${scraper.concurrency.amazon:3}")
    private int amazonPermits;

    @Value("${scraper.concurrency.flipkart:3}")
    private int flipkartPermits;

    @Value("${scraper.concurrency.default:5}")
    private int defaultPermits;

    private final Counter acquiredCounter;
    private final Counter timedOutCounter;

    public PlatformConcurrencyLimiter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        this.acquiredCounter = Counter.builder("pricepulse.concurrency.semaphore.acquired")
                .description("Total permits acquired across all platforms")
                .register(meterRegistry);

        this.timedOutCounter = Counter.builder("pricepulse.concurrency.semaphore.timedout")
                .description("Permit acquisition timeouts due to platform concurrency saturation")
                .register(meterRegistry);
    }

    /**
     * Attempts to acquire a concurrency permit for the specified platform.
     * Blocking on a Semaphore in a Java 21 Virtual Thread unmounts the carrier thread cleanly.
     *
     * @param platform domain name (e.g. "amazon", "flipkart")
     * @param timeout maximum time to wait for a permit
     * @return true if permit acquired, false if timed out
     */
    public boolean tryAcquire(String platform, Duration timeout) throws InterruptedException {
        String normalizedPlatform = normalizePlatform(platform);
        Semaphore semaphore = getOrCreateSemaphore(normalizedPlatform);

        boolean acquired = semaphore.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (acquired) {
            acquiredCounter.increment();
            logger.debug("Acquired concurrency permit for platform '{}'. Available permits: {}",
                    normalizedPlatform, semaphore.availablePermits());
        } else {
            timedOutCounter.increment();
            logger.warn("Timed out waiting for concurrency permit for platform '{}'", normalizedPlatform);
        }
        return acquired;
    }

    /**
     * Releases the acquired permit back to the platform's semaphore.
     */
    public void release(String platform) {
        String normalizedPlatform = normalizePlatform(platform);
        Semaphore semaphore = semaphores.get(normalizedPlatform);
        if (semaphore != null) {
            semaphore.release();
            logger.debug("Released concurrency permit for platform '{}'. Available permits: {}",
                    normalizedPlatform, semaphore.availablePermits());
        }
    }

    @PostConstruct
    public void init() {
        getOrCreateSemaphore("amazon");
        getOrCreateSemaphore("flipkart");
    }

    public int getAvailablePermits(String platform) {
        String normalized = normalizePlatform(platform);
        return getOrCreateSemaphore(normalized).availablePermits();
    }

    private Semaphore getOrCreateSemaphore(String platform) {
        return semaphores.computeIfAbsent(platform, p -> {
            registryLock.lock();
            try {
                int permits = resolvePermitsForPlatform(p);
                Semaphore newSemaphore = new Semaphore(permits, true); // Fair semaphore
                Gauge.builder("pricepulse.concurrency.permits.available", newSemaphore, Semaphore::availablePermits)
                        .tag("platform", p)
                        .description("Available concurrent outbound permits")
                        .register(meterRegistry);
                logger.info("Initialized concurrency semaphore for platform '{}' with {} permits", p, permits);
                return newSemaphore;
            } finally {
                registryLock.unlock();
            }
        });
    }

    private int resolvePermitsForPlatform(String platform) {
        return switch (platform) {
            case "amazon" -> amazonPermits;
            case "flipkart" -> flipkartPermits;
            default -> defaultPermits;
        };
    }

    private String normalizePlatform(String platform) {
        if (platform == null) return "default";
        String lower = platform.toLowerCase().trim();
        if (lower.contains("amazon")) return "amazon";
        if (lower.contains("flipkart")) return "flipkart";
        return lower;
    }
}
