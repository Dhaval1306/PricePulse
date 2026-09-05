package com.pricepulse.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ConcurrencyConfig {

    /**
     * Dedicated Java 21 Virtual Thread executor for asynchronous, I/O-bound scraping tasks.
     * Each submitted task spawns a lightweight virtual thread that unmounts from its carrier
     * thread during socket I/O and jitter sleeps, eliminating platform thread starvation.
     */
    @Bean(name = "scrapingVirtualThreadExecutor", destroyMethod = "close")
    public ExecutorService scrapingVirtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
