package com.pricepulse.scheduler;

import com.pricepulse.concurrency.PlatformConcurrencyLimiter;
import com.pricepulse.dto.PriceDeltaResult;
import com.pricepulse.dto.ScrapedProductDto;
import com.pricepulse.entity.Product;
import com.pricepulse.entity.UserProductSubscription;
import com.pricepulse.repository.ProductRepository;
import com.pricepulse.repository.UserProductSubscriptionRepository;
import com.pricepulse.service.AlertNotificationService;
import com.pricepulse.service.PriceCacheService;
import com.pricepulse.service.RateLimiterService;
import com.pricepulse.service.ScraperService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

@Component
public class PriceMonitorScheduler {

    private static final Logger logger = LoggerFactory.getLogger(PriceMonitorScheduler.class);

    private final ProductRepository productRepository;
    private final UserProductSubscriptionRepository subscriptionRepository;
    private final PriceCacheService priceCacheService;
    private final ScraperService scraperService;
    private final RateLimiterService rateLimiterService;
    private final AlertNotificationService alertNotificationService;
    private final PlatformConcurrencyLimiter concurrencyLimiter;
    private final ExecutorService virtualThreadExecutor;

    private final Counter schedulerRunsCounter;
    private final Counter productsDispatchedCounter;
    private final Timer batchExecutionTimer;

    public PriceMonitorScheduler(
            ProductRepository productRepository,
            UserProductSubscriptionRepository subscriptionRepository,
            PriceCacheService priceCacheService,
            ScraperService scraperService,
            RateLimiterService rateLimiterService,
            AlertNotificationService alertNotificationService,
            PlatformConcurrencyLimiter concurrencyLimiter,
            @Qualifier("scrapingVirtualThreadExecutor") ExecutorService virtualThreadExecutor,
            MeterRegistry meterRegistry) {
        this.productRepository = productRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.priceCacheService = priceCacheService;
        this.scraperService = scraperService;
        this.rateLimiterService = rateLimiterService;
        this.alertNotificationService = alertNotificationService;
        this.concurrencyLimiter = concurrencyLimiter;
        this.virtualThreadExecutor = virtualThreadExecutor;

        this.schedulerRunsCounter = Counter.builder("pricepulse.scheduler.runs.total")
                .description("Total scheduler batch runs initiated")
                .register(meterRegistry);

        this.productsDispatchedCounter = Counter.builder("pricepulse.scheduler.products.dispatched")
                .description("Total product scraping tasks dispatched to virtual threads")
                .register(meterRegistry);

        this.batchExecutionTimer = Timer.builder("pricepulse.scheduler.batch.duration")
                .description("Total duration taken to dispatch all products in a cycle")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${scheduler.interval-ms:300000}")
    public void dispatchScrapingTasks() {
        batchExecutionTimer.record(() -> {
            schedulerRunsCounter.increment();
            List<Product> products = productRepository.findAll();

            if (products.isEmpty()) {
                logger.debug("PricePulse Scheduler: No products registered for monitoring.");
                return;
            }

            logger.info("PricePulse Scheduler: Dispatching {} products across Java 21 Virtual Threads.", products.size());

            for (Product product : products) {
                productsDispatchedCounter.increment();
                virtualThreadExecutor.submit(() -> executeProductPipeline(product));
            }
        });
    }

    /**
     * Executes the complete product monitoring pipeline inside an isolated virtual thread:
     * 1. Acquire per-platform Semaphore permit (in-process concurrency bound).
     * 2. Check distributed Sliding Window Rate Limiter (cluster throughput bound).
     *    - If throttled: skips task for this cycle (defers to next run, avoiding herd wakeups).
     * 3. Acquire Redis distributed lock (SETNX) to prevent race conditions on the same product.
     * 4. Scrape product HTML with Jsoup, rotating headers, and Resilience4j circuit breaker.
     * 5. Perform Redis Delta-Cache check (compare-on-write, sliding TTL, and DB write-avoidance).
     * 6. Dispatch alerts on price drops or target threshold hits.
     */
    private void executeProductPipeline(Product product) {
        String platform = product.getPlatform() != null ? product.getPlatform() : "default";
        boolean permitAcquired = false;

        try {
            // Step 1: Per-Platform Semaphore Concurrency Cap
            permitAcquired = concurrencyLimiter.tryAcquire(platform, Duration.ofSeconds(30));
            if (!permitAcquired) {
                logger.warn("Rate-limit backpressure: Concurrency limit saturated for '{}'. Skipping product {}",
                        platform, product.getId());
                return;
            }

            // Step 2: Distributed Sliding-Window Rate Limiter (Redis Lua)
            boolean allowed = rateLimiterService.tryAcquire(platform);
            if (!allowed) {
                logger.info("Sliding Window Rate Limiter [THROTTLED]: Quota reached for platform '{}'. Skipping product {} for this cycle.",
                        platform, product.getId());
                // Fast-fail & defer to next schedule: avoids in-place herd sleep at window boundary
                return;
            }

            // Step 3: Redis SETNX Distributed Lock
            Optional<String> lockTokenOpt = priceCacheService.acquireLock(product.getId(), 20);
            if (lockTokenOpt.isEmpty()) {
                logger.debug("Product {} is currently locked by another worker. Skipping.", product.getId());
                return;
            }

            String lockToken = lockTokenOpt.get();
            try {
                // Step 4: Resilient Scraping Execution
                ScrapedProductDto scraped = scraperService.scrape(product.getUrl());

                if (scraped == null || scraped.price() == null) {
                    logger.warn("Scraper returned empty result for product {}", product.getId());
                    return;
                }

                // Step 5: Redis Delta-Cache Processing (compare-on-write & sliding TTL)
                PriceDeltaResult deltaResult = priceCacheService.processPriceUpdate(
                        product.getId(),
                        scraped.price(),
                        scraped.isInStock()
                );

                // Step 6: Alert Dispatching
                if (deltaResult.priceChanged() || deltaResult.stockChanged()) {
                    dispatchAlertsIfApplicable(product, deltaResult);
                }

            } finally {
                // Always release the distributed lock with atomic Lua script
                priceCacheService.releaseLock(product.getId(), lockToken);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Virtual thread interrupted during pipeline execution for product {}", product.getId());
        } catch (Exception e) {
            logger.error("Pipeline failure for product {}: {}", product.getId(), e.getMessage());
        } finally {
            // Always release the platform concurrency permit
            if (permitAcquired) {
                concurrencyLimiter.release(platform);
            }
        }
    }

    private void dispatchAlertsIfApplicable(Product product, PriceDeltaResult deltaResult) {
        List<UserProductSubscription> subscriptions =
                subscriptionRepository.findByProductAndIsActiveTrue(product);

        for (UserProductSubscription subscription : subscriptions) {
            if (deltaResult.oldPrice() != null && deltaResult.newPrice().compareTo(deltaResult.oldPrice()) < 0) {
                alertNotificationService.sendPriceDropAlert(
                        subscription.getUser(),
                        product,
                        deltaResult.oldPrice(),
                        deltaResult.newPrice()
                );
            }

            if (subscription.getTargetPrice() != null &&
                    deltaResult.newPrice().compareTo(subscription.getTargetPrice()) <= 0) {
                alertNotificationService.sendTargetThresholdAlert(
                        subscription.getUser(),
                        product,
                        subscription.getTargetPrice(),
                        deltaResult.newPrice()
                );
            }
        }
    }
}
