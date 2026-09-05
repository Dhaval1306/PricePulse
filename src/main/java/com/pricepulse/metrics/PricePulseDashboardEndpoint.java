package com.pricepulse.metrics;

import com.pricepulse.concurrency.PlatformConcurrencyLimiter;
import com.pricepulse.service.RateLimiterService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Endpoint(id = "pricepulse")
public class PricePulseDashboardEndpoint {

    private final MeterRegistry meterRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final PlatformConcurrencyLimiter concurrencyLimiter;
    private final RateLimiterService rateLimiterService;

    public PricePulseDashboardEndpoint(
            MeterRegistry meterRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry,
            PlatformConcurrencyLimiter concurrencyLimiter,
            RateLimiterService rateLimiterService) {
        this.meterRegistry = meterRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.concurrencyLimiter = concurrencyLimiter;
        this.rateLimiterService = rateLimiterService;
    }

    @ReadOperation
    public Map<String, Object> getMetricsDashboard() {
        Map<String, Object> dashboard = new LinkedHashMap<>();

        // 1. Delta-Cache Metrics
        Counter totalScrapes = meterRegistry.find("pricepulse.scrapes.total").counter();
        Counter writesAvoided = meterRegistry.find("pricepulse.db.writes.avoided").counter();
        Counter writesCommitted = meterRegistry.find("pricepulse.db.writes.committed").counter();
        Counter lockContention = meterRegistry.find("pricepulse.lock.contention").counter();

        double total = (totalScrapes != null) ? totalScrapes.count() : 0.0;
        double avoided = (writesAvoided != null) ? writesAvoided.count() : 0.0;
        double committed = (writesCommitted != null) ? writesCommitted.count() : 0.0;
        double contention = (lockContention != null) ? lockContention.count() : 0.0;
        double writeAvoidanceRate = (total > 0) ? (avoided / total) * 100.0 : 0.0;

        Map<String, Object> cacheSection = new LinkedHashMap<>();
        cacheSection.put("totalScrapesProcessed", (long) total);
        cacheSection.put("databaseWritesAvoided", (long) avoided);
        cacheSection.put("databaseWritesCommitted", (long) committed);
        cacheSection.put("writeAvoidanceRate", String.format("%.2f%%", writeAvoidanceRate));
        cacheSection.put("targetBenchmarkClaim", "~85% write reduction");
        cacheSection.put("lockContentionInstances", (long) contention);
        dashboard.put("deltaCachePerformance", cacheSection);

        // 2. Circuit Breakers Status
        Map<String, Object> cbSection = new LinkedHashMap<>();
        for (CircuitBreaker cb : circuitBreakerRegistry.getAllCircuitBreakers()) {
            Map<String, Object> cbInfo = new LinkedHashMap<>();
            cbInfo.put("state", cb.getState().name());
            cbInfo.put("failureRateThreshold", cb.getCircuitBreakerConfig().getFailureRateThreshold() + "%");
            cbInfo.put("slidingWindowSize", cb.getCircuitBreakerConfig().getSlidingWindowSize());
            cbSection.put(cb.getName(), cbInfo);
        }
        dashboard.put("resilience4jCircuitBreakers", cbSection);

        // 3. Concurrency & Rate Limiting Status
        Map<String, Object> rateLimitSection = new LinkedHashMap<>();
        rateLimitSection.put("amazonRemainingQuota", rateLimiterService.getRemainingQuota("amazon"));
        rateLimitSection.put("flipkartRemainingQuota", rateLimiterService.getRemainingQuota("flipkart"));
        rateLimitSection.put("amazonAvailableConcurrencyPermits", concurrencyLimiter.getAvailablePermits("amazon"));
        rateLimitSection.put("flipkartAvailableConcurrencyPermits", concurrencyLimiter.getAvailablePermits("flipkart"));
        dashboard.put("trafficManagement", rateLimitSection);

        // 4. Concurrency Architecture Metadata
        Map<String, Object> concurrencySection = new LinkedHashMap<>();
        concurrencySection.put("threadingModel", "Java 21 Virtual Threads (Project Loom)");
        concurrencySection.put("carrierPinningSafeguard", "ReentrantLock enforced (zero synchronized blocks)");
        concurrencySection.put("distributedLocking", "Redis SETNX with atomic Lua release");
        concurrencySection.put("rateLimiterAlgorithm", "Sliding Window Log via Redis Sorted Sets");
        dashboard.put("systemArchitecture", concurrencySection);

        return dashboard;
    }
}
