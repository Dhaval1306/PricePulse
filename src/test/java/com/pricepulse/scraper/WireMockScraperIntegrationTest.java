package com.pricepulse.scraper;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pricepulse.dto.ScrapedProductDto;
import com.pricepulse.scraper.impl.AmazonExtractor;
import com.pricepulse.service.impl.ScraperServiceImpl;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

class WireMockScraperIntegrationTest {

    private WireMockServer wireMockServer;
    private ScraperServiceImpl scraperService;
    private CircuitBreakerRegistry circuitBreakerRegistry;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();

        meterRegistry = new SimpleMeterRegistry();

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(java.time.Duration.ofMillis(500))
                .permittedNumberOfCallsInHalfOpenState(1)
                .build();

        circuitBreakerRegistry = CircuitBreakerRegistry.of(cbConfig);

        AmazonExtractor amazonExtractor = new AmazonExtractor(meterRegistry);
        scraperService = new ScraperServiceImpl(
                List.of(amazonExtractor),
                circuitBreakerRegistry,
                meterRegistry
        );

        // Fast jitter & retries for unit test execution
        ReflectionTestUtils.setField(scraperService, "minJitterMs", 5L);
        ReflectionTestUtils.setField(scraperService, "maxJitterMs", 10L);
        ReflectionTestUtils.setField(scraperService, "maxRetryAttempts", 2);
        ReflectionTestUtils.setField(scraperService, "backoffBaseMs", 10L);
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    @DisplayName("Should scrape product details accurately from mocked Amazon HTML")
    void testScraperSuccessWithMockHtml() {
        String mockHtml = """
                <html>
                <body>
                    <h1 id="title"><span id="productTitle">Sony WH-1000XM5 Wireless Headphones</span></h1>
                    <span class="a-price-whole">26,990.00</span>
                    <div id="availability"><span>In stock</span></div>
                    <img id="landingImage" src="https://images.example.com/sony.jpg" />
                </body>
                </html>
                """;

        wireMockServer.stubFor(get(urlPathEqualTo("/mock-amazon/dp/B09XS7JWHH"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html; charset=UTF-8")
                        .withBody(mockHtml)));

        String mockUrl = "http://localhost:" + wireMockServer.port() + "/mock-amazon/dp/B09XS7JWHH";

        ScrapedProductDto result = scraperService.scrape(mockUrl);

        assertNotNull(result);
        assertEquals("Sony WH-1000XM5 Wireless Headphones", result.title());
        assertEquals(new BigDecimal("26990.00"), result.price());
        assertTrue(result.isInStock());
        assertEquals("https://images.example.com/sony.jpg", result.imageUrl());
        assertEquals("amazon", result.platform());
    }

    @Test
    @DisplayName("Should trip Circuit Breaker to OPEN when consecutive 503 errors exceed threshold")
    void testCircuitBreakerTripsOnConsecutiveFailures() {
        wireMockServer.stubFor(get(urlPathEqualTo("/mock-amazon/dp/fail"))
                .willReturn(aResponse().withStatus(503)));

        String failUrl = "http://localhost:" + wireMockServer.port() + "/mock-amazon/dp/fail";

        // Call 1 & 2 fail with 503
        assertThrows(RuntimeException.class, () -> scraperService.scrape(failUrl));
        assertThrows(RuntimeException.class, () -> scraperService.scrape(failUrl));

        // Verify Circuit Breaker tripped to OPEN
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("amazon");
        assertEquals(CircuitBreaker.State.OPEN, cb.getState(),
                "Circuit breaker should transition to OPEN state after consecutive failures");
    }
}
