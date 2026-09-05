package com.pricepulse.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PricePulseMetricsTest {

    @Test
    @DisplayName("Should dynamically compute write-avoidance rate matching target benchmark")
    void testWriteAvoidanceRateCalculation() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PricePulseMetricsConfig(registry);

        Counter totalScrapes = Counter.builder("pricepulse.scrapes.total").register(registry);
        Counter writesAvoided = Counter.builder("pricepulse.db.writes.avoided").register(registry);

        // Simulate 1000 scrapes with 857 avoided writes (85.7%)
        totalScrapes.increment(1000);
        writesAvoided.increment(857);

        Gauge avoidanceRateGauge = registry.find("pricepulse.cache.write_avoidance_rate").gauge();
        assertNotNull(avoidanceRateGauge);
        assertEquals(85.7, avoidanceRateGauge.value(), 0.01,
                "Write avoidance rate should accurately reflect 85.7%");
    }
}
