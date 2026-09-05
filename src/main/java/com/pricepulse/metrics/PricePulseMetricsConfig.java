package com.pricepulse.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PricePulseMetricsConfig {

    public PricePulseMetricsConfig(MeterRegistry registry) {
        // Dynamic Gauge for DB Write-Avoidance Rate percentage
        Gauge.builder("pricepulse.cache.write_avoidance_rate", () -> {
            Counter total = registry.find("pricepulse.scrapes.total").counter();
            Counter avoided = registry.find("pricepulse.db.writes.avoided").counter();
            if (total == null || total.count() == 0 || avoided == null) {
                return 0.0;
            }
            return (avoided.count() / total.count()) * 100.0;
        })
        .description("Empirical percentage of PostgreSQL writes eliminated by Redis delta-cache")
        .baseUnit("percent")
        .register(registry);
    }
}
