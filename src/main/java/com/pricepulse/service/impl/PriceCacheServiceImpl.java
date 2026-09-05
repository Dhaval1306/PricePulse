package com.pricepulse.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricepulse.dto.PriceDeltaResult;
import com.pricepulse.dto.ProductCacheState;
import com.pricepulse.entity.PriceHistory;
import com.pricepulse.entity.Product;
import com.pricepulse.repository.PriceHistoryRepository;
import com.pricepulse.repository.ProductRepository;
import com.pricepulse.service.PriceCacheService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

@Service
public class PriceCacheServiceImpl implements PriceCacheService {

    private static final Logger logger = LoggerFactory.getLogger(PriceCacheServiceImpl.class);

    private static final String CACHE_KEY_PREFIX = "pricepulse:product:";
    private static final String CACHE_KEY_SUFFIX = ":state";
    private static final String LOCK_KEY_PREFIX = "lock:product:";

    // Atomic Lua script to release lock only if the token matches
    private static final String RELEASE_LOCK_LUA_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('del', KEYS[1]) " +
            "else " +
            "    return 0 " +
            "end";

    private final StringRedisTemplate redisTemplate;
    private final ProductRepository productRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final ObjectMapper objectMapper;

    @Value("${cache.redis.ttl-seconds:86400}")
    private long cacheTtlSeconds;

    // Micrometer Observability Counters
    private final Counter totalScrapesCounter;
    private final Counter writesAvoidedCounter;
    private final Counter writesCommittedCounter;
    private final Counter lockContentionCounter;

    public PriceCacheServiceImpl(
            StringRedisTemplate redisTemplate,
            ProductRepository productRepository,
            PriceHistoryRepository priceHistoryRepository,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.productRepository = productRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.objectMapper = objectMapper;

        this.totalScrapesCounter = Counter.builder("pricepulse.scrapes.total")
                .description("Total scrape results processed by the delta-cache")
                .register(meterRegistry);

        this.writesAvoidedCounter = Counter.builder("pricepulse.db.writes.avoided")
                .description("PostgreSQL writes avoided due to unchanged price/stock")
                .register(meterRegistry);

        this.writesCommittedCounter = Counter.builder("pricepulse.db.writes.committed")
                .description("PostgreSQL writes committed due to genuine price or stock shifts")
                .register(meterRegistry);

        this.lockContentionCounter = Counter.builder("pricepulse.lock.contention")
                .description("Instances where product distributed lock acquisition failed")
                .register(meterRegistry);
    }

    @Override
    public Optional<String> acquireLock(Long productId, long timeoutSeconds) {
        String lockKey = LOCK_KEY_PREFIX + productId;
        String lockToken = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                    lockKey,
                    lockToken,
                    Duration.ofSeconds(timeoutSeconds)
            );
            if (Boolean.TRUE.equals(acquired)) {
                logger.debug("Acquired distributed lock for product {} with token {}", productId, lockToken);
                return Optional.of(lockToken);
            }
            logger.warn("Distributed lock acquisition contested for product {}", productId);
            lockContentionCounter.increment();
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Redis error while acquiring lock for product {}: {}", productId, e.getMessage());
            // In case of Redis outage, return empty to avoid uncoordinated writes
            return Optional.empty();
        }
    }

    @Override
    public void releaseLock(Long productId, String lockToken) {
        String lockKey = LOCK_KEY_PREFIX + productId;
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(RELEASE_LOCK_LUA_SCRIPT, Long.class);
            Long result = redisTemplate.execute(script, Collections.singletonList(lockKey), lockToken);
            if (result != null && result == 1L) {
                logger.debug("Successfully released lock for product {} with token {}", productId, lockToken);
            } else {
                logger.warn("Lock for product {} was already expired or owned by another process", productId);
            }
        } catch (Exception e) {
            logger.error("Redis error while releasing lock for product {}: {}", productId, e.getMessage());
        }
    }

    @Override
    @Transactional
    public PriceDeltaResult processPriceUpdate(Long productId, BigDecimal scrapedPrice, boolean inStock) {
        totalScrapesCounter.increment();
        String cacheKey = CACHE_KEY_PREFIX + productId + CACHE_KEY_SUFFIX;

        ProductCacheState cachedState = getOrSeedCacheState(productId, cacheKey);
        if (cachedState == null) {
            throw new IllegalArgumentException("Cannot resolve product state for ID: " + productId);
        }

        BigDecimal oldPrice = cachedState.price();
        boolean oldStock = cachedState.inStock();

        // BigDecimal numerical comparison (ignores scale, e.g. 100.0 vs 100.00)
        boolean priceChanged = (oldPrice == null) || (oldPrice.compareTo(scrapedPrice) != 0);
        boolean stockChanged = (oldStock != inStock);

        if (!priceChanged && !stockChanged) {
            // Unchanged: refresh last checked timestamp in cache and RESET TTL on every touch
            ProductCacheState updatedTouchState = new ProductCacheState(oldPrice, oldStock, System.currentTimeMillis());
            writeToRedis(cacheKey, updatedTouchState, cacheTtlSeconds);

            writesAvoidedCounter.increment();
            logger.info("Delta-Cache [HIT - NO CHANGE]: Product {} price remains {} (DB write avoided)", productId, scrapedPrice);
            return new PriceDeltaResult(false, false, oldPrice, scrapedPrice, true);
        }

        // Genuine delta detected: commit to PostgreSQL, then update Redis
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

        product.setCurrentPrice(scrapedPrice);
        product.setIsInStock(inStock);
        product.setLastCheckedAt(OffsetDateTime.now());
        productRepository.save(product);

        PriceHistory snapshot = new PriceHistory(product, scrapedPrice, inStock);
        priceHistoryRepository.save(snapshot);

        // Update Redis cache with new state and sliding TTL extension
        ProductCacheState newState = new ProductCacheState(scrapedPrice, inStock, System.currentTimeMillis());
        writeToRedis(cacheKey, newState, cacheTtlSeconds);

        writesCommittedCounter.increment();
        logger.info("Delta-Cache [DELTA DETECTED]: Product {} shifted from {} to {} (DB snapshot committed)",
                productId, oldPrice, scrapedPrice);

        return new PriceDeltaResult(priceChanged, stockChanged, oldPrice, scrapedPrice, false);
    }

    @Override
    public Optional<ProductCacheState> getCachedState(Long productId) {
        String cacheKey = CACHE_KEY_PREFIX + productId + CACHE_KEY_SUFFIX;
        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json != null) {
                return Optional.of(objectMapper.readValue(json, ProductCacheState.class));
            }
        } catch (Exception e) {
            logger.warn("Failed to read cache for product {}: {}", productId, e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public void evictCachedState(Long productId) {
        String cacheKey = CACHE_KEY_PREFIX + productId + CACHE_KEY_SUFFIX;
        try {
            redisTemplate.delete(cacheKey);
            logger.debug("Evicted cache for product {}", productId);
        } catch (Exception e) {
            logger.error("Failed to evict cache for product {}: {}", productId, e.getMessage());
        }
    }

    private ProductCacheState getOrSeedCacheState(Long productId, String cacheKey) {
        // Step 1: Check Redis
        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json != null) {
                return objectMapper.readValue(json, ProductCacheState.class);
            }
        } catch (Exception e) {
            logger.warn("Redis read failure for product {}: {}. Falling back to PostgreSQL.", productId, e.getMessage());
        }

        // Step 2: Cache Miss -> Fallback to PostgreSQL
        logger.info("Delta-Cache [MISS]: Seeding Redis for product {} from PostgreSQL single-source-of-truth", productId);
        Product product = productRepository.findById(productId).orElse(null);
        if (product == null) {
            return null;
        }

        ProductCacheState seededState = new ProductCacheState(
                product.getCurrentPrice(),
                product.getIsInStock(),
                System.currentTimeMillis()
        );

        // Seed into Redis with TTL
        writeToRedis(cacheKey, seededState, cacheTtlSeconds);
        return seededState;
    }

    private void writeToRedis(String key, ProductCacheState state, long ttlSeconds) {
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds));
        } catch (JsonProcessingException e) {
            logger.error("JSON serialization failure for key {}: {}", key, e.getMessage());
        } catch (Exception e) {
            logger.warn("Redis write failure for key {}: {}", key, e.getMessage());
        }
    }
}
