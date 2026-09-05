package com.pricepulse.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.fppt.jedismock.RedisServer;
import com.pricepulse.dto.PriceDeltaResult;
import com.pricepulse.entity.PriceHistory;
import com.pricepulse.entity.Product;
import com.pricepulse.repository.PriceHistoryRepository;
import com.pricepulse.repository.ProductRepository;
import com.pricepulse.service.impl.PriceCacheServiceImpl;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PriceCacheServiceTest {

    private RedisServer redisServer;
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private PriceHistoryRepository priceHistoryRepository;

    private SimpleMeterRegistry meterRegistry;
    private PriceCacheServiceImpl priceCacheService;

    @BeforeEach
    void setUp() throws IOException {
        redisServer = RedisServer.newRedisServer().start();

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                redisServer.getHost(),
                redisServer.getBindPort()
        );
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        meterRegistry = new SimpleMeterRegistry();
        priceCacheService = new PriceCacheServiceImpl(
                redisTemplate,
                productRepository,
                priceHistoryRepository,
                mapper,
                meterRegistry
        );

        ReflectionTestUtils.setField(priceCacheService, "cacheTtlSeconds", 86400L);
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
    @DisplayName("Should acquire SETNX lock and fail on concurrent contention")
    void testDistributedLockContention() {
        Long productId = 1001L;

        // Worker A acquires lock
        Optional<String> tokenA = priceCacheService.acquireLock(productId, 10);
        assertTrue(tokenA.isPresent(), "Worker A should acquire lock successfully");

        // Worker B attempts to acquire same product lock -> contested
        Optional<String> tokenB = priceCacheService.acquireLock(productId, 10);
        assertTrue(tokenB.isEmpty(), "Worker B must be rejected while lock is held");

        // Worker A releases lock
        priceCacheService.releaseLock(productId, tokenA.get());

        // Now Worker B can acquire
        Optional<String> tokenBAfterRelease = priceCacheService.acquireLock(productId, 10);
        assertTrue(tokenBAfterRelease.isPresent(), "Worker B should acquire lock after release");
        priceCacheService.releaseLock(productId, tokenBAfterRelease.get());
    }

    @Test
    @DisplayName("Delta-Cache Write-Avoidance: Unchanged price should avoid PostgreSQL write")
    void testWriteAvoidanceOnUnchangedPrice() {
        Long productId = 501L;
        Product product = new Product("https://amazon.in/dp/test", "amazon", "Test Laptop", new BigDecimal("49999.00"));
        product.setId(productId);

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        // Cycle 1: Price unchanged from database current price -> seeds cache and avoids DB write
        PriceDeltaResult result1 = priceCacheService.processPriceUpdate(productId, new BigDecimal("49999.00"), true);
        assertTrue(result1.writeAvoided(), "Identical price must avoid database write");
        verify(priceHistoryRepository, never()).save(any(PriceHistory.class));

        // Cycle 2: Same price scraped again -> HIT - NO CHANGE in cache
        PriceDeltaResult result2 = priceCacheService.processPriceUpdate(productId, new BigDecimal("49999.00"), true);
        assertTrue(result2.writeAvoided(), "Second identical check must also avoid write");
        verify(priceHistoryRepository, never()).save(any(PriceHistory.class));

        // Verify Micrometer counter registered avoided writes
        Counter avoidedCounter = meterRegistry.find("pricepulse.db.writes.avoided").counter();
        assertNotNull(avoidedCounter);
        assertEquals(2.0, avoidedCounter.count(), "Should have recorded 2 avoided writes");
    }

    @Test
    @DisplayName("Delta Detection: Genuine price drop must commit to PostgreSQL and update cache")
    void testPriceDeltaCommit() {
        Long productId = 601L;
        Product product = new Product("https://amazon.in/dp/test2", "amazon", "Test Phone", new BigDecimal("20000.00"));
        product.setId(productId);

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        // Cycle 1: Baseline established at 20000
        priceCacheService.processPriceUpdate(productId, new BigDecimal("20000.00"), true);

        // Cycle 2: Price drops to 18000 -> Genuine change
        PriceDeltaResult deltaResult = priceCacheService.processPriceUpdate(productId, new BigDecimal("18000.00"), true);

        assertTrue(deltaResult.priceChanged(), "Delta must be recognized");
        assertFalse(deltaResult.writeAvoided(), "Delta requires database commit");
        assertEquals(new BigDecimal("20000.00"), deltaResult.oldPrice());
        assertEquals(new BigDecimal("18000.00"), deltaResult.newPrice());

        // Verifies PostgreSQL snapshot was saved
        verify(priceHistoryRepository, times(1)).save(any(PriceHistory.class));
        verify(productRepository, times(1)).save(product);

        Counter committedCounter = meterRegistry.find("pricepulse.db.writes.committed").counter();
        assertNotNull(committedCounter);
        assertEquals(1.0, committedCounter.count(), "Should have recorded 1 committed write");
    }
}
