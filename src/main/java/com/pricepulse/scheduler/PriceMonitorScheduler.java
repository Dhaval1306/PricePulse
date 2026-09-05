package com.pricepulse.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PriceMonitorScheduler {

    private static final Logger logger = LoggerFactory.getLogger(PriceMonitorScheduler.class);

    @Scheduled(fixedDelayString = "${scheduler.interval-ms:300000}")
    public void dispatchScrapingTasks() {
        // To be implemented in Phase 2 Module 3 with Virtual Threads
        logger.debug("Price monitor scheduled tick executed.");
    }
}
