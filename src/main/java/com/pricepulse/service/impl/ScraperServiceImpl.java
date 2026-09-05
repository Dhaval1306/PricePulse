package com.pricepulse.service.impl;

import com.pricepulse.dto.ScrapedProductDto;
import com.pricepulse.scraper.PlatformExtractor;
import com.pricepulse.scraper.ScraperUtils;
import com.pricepulse.service.ScraperService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ScraperServiceImpl implements ScraperService {

    private static final Logger logger = LoggerFactory.getLogger(ScraperServiceImpl.class);

    // Note: robots.txt and site Terms of Service (ToS) parsing are out-of-scope for this educational portfolio project.

    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:130.0) Gecko/20100101 Firefox/130.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:130.0) Gecko/20100101 Firefox/130.0",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 Edg/128.0.0.0"
    );

    private final List<PlatformExtractor> extractors;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final Counter totalRequestsCounter;
    private final Counter failedRequestsCounter;

    @Value("${scraper.jitter.min-ms:1000}")
    private long minJitterMs;

    @Value("${scraper.jitter.max-ms:3000}")
    private long maxJitterMs;

    @Value("${scraper.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${scraper.retry.backoff-base-ms:1500}")
    private long backoffBaseMs;

    public ScraperServiceImpl(
            List<PlatformExtractor> extractors,
            CircuitBreakerRegistry circuitBreakerRegistry,
            MeterRegistry meterRegistry) {
        this.extractors = extractors;
        this.circuitBreakerRegistry = circuitBreakerRegistry;

        this.totalRequestsCounter = Counter.builder("pricepulse.scraper.requests.total")
                .description("Total outbound HTTP scrape requests dispatched")
                .register(meterRegistry);

        this.failedRequestsCounter = Counter.builder("pricepulse.scraper.requests.failed")
                .description("Total failed scrape requests after exhausting retries or circuit breaker")
                .register(meterRegistry);
    }

    @Override
    public boolean supportsDomain(String domainOrUrl) {
        return extractors.stream().anyMatch(e -> e.supports(domainOrUrl));
    }

    @Override
    public ScrapedProductDto scrape(String productUrl) {
        totalRequestsCounter.increment();

        PlatformExtractor extractor = extractors.stream()
                .filter(e -> e.supports(productUrl))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No extractor available for URL: " + productUrl));

        String platform = extractor.getPlatformName();
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(platform);

        try {
            // Execute within platform-specific Circuit Breaker
            return circuitBreaker.executeSupplier(() -> fetchWithRetryAndJitter(productUrl, extractor));
        } catch (CallNotPermittedException e) {
            failedRequestsCounter.increment();
            logger.error("Circuit Breaker is OPEN for platform '{}'. Fast-failing request for URL: {}", platform, productUrl);
            throw new RuntimeException("Scraping temporarily disabled due to elevated platform failure rates: " + platform, e);
        } catch (Exception e) {
            failedRequestsCounter.increment();
            logger.error("Scraping pipeline failed for URL {}: {}", productUrl, e.getMessage());
            throw new RuntimeException("Failed to scrape product URL: " + productUrl, e);
        }
    }

    private ScrapedProductDto fetchWithRetryAndJitter(String productUrl, PlatformExtractor extractor) {
        int attempt = 0;
        Exception lastException = null;

        while (attempt < maxRetryAttempts) {
            attempt++;
            try {
                // Step 1: Apply randomized jitter delay before firing request
                applyJitterDelay();

                // Step 2: Select random User-Agent and headers
                String userAgent = getRandomUserAgent();

                logger.debug("Executing scrape attempt {}/{} for URL: {} with User-Agent: {}",
                        attempt, maxRetryAttempts, productUrl, userAgent);

                Connection connection = Jsoup.connect(productUrl)
                        .userAgent(userAgent)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .header("Sec-Ch-Ua", "\"Chromium\";v=\"128\", \"Not;A=Brand\";v=\"24\"")
                        .header("Sec-Ch-Ua-Mobile", "?0")
                        .header("Sec-Ch-Ua-Platform", "\"Windows\"")
                        .header("Sec-Fetch-Dest", "document")
                        .header("Sec-Fetch-Mode", "navigate")
                        .header("Sec-Fetch-Site", "none")
                        .header("Upgrade-Insecure-Requests", "1")
                        .timeout(10000)
                        .followRedirects(true);

                Document doc = connection.get();

                // Step 3: Extract structured product details
                return extractor.extract(doc, productUrl);

            } catch (IOException e) {
                lastException = e;
                logger.warn("Scrape attempt {}/{} failed for {}: {}", attempt, maxRetryAttempts, productUrl, e.getMessage());

                if (attempt < maxRetryAttempts) {
                    // Exponential backoff with full jitter formula: random(0, base * 2^attempt)
                    long maxBackoff = backoffBaseMs * (1L << attempt);
                    long backoffWithJitter = ThreadLocalRandom.current().nextLong(maxBackoff / 2, maxBackoff + 1);
                    logger.info("Applying exponential backoff of {}ms before retry attempt {}", backoffWithJitter, attempt + 1);
                    try {
                        Thread.sleep(backoffWithJitter);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Scraping interrupted during backoff", ie);
                    }
                }
            }
        }

        throw new RuntimeException("Exhausted all " + maxRetryAttempts + " scrape attempts for " + productUrl, lastException);
    }

    private void applyJitterDelay() {
        if (maxJitterMs > minJitterMs) {
            long jitter = ThreadLocalRandom.current().nextLong(minJitterMs, maxJitterMs + 1);
            try {
                Thread.sleep(jitter);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private String getRandomUserAgent() {
        int index = ThreadLocalRandom.current().nextInt(USER_AGENTS.size());
        return USER_AGENTS.get(index);
    }
}
