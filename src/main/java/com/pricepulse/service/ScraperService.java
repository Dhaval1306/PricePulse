package com.pricepulse.service;

import com.pricepulse.dto.ScrapedProductDto;

public interface ScraperService {

    /**
     * Executes resilient scraping of the given product URL.
     * Incorporates rotating headers, randomized jitter, exponential backoff retry,
     * and Resilience4j circuit breakers per platform.
     *
     * @param productUrl URL of the product to scrape
     * @return ScrapedProductDto with extracted details
     */
    ScrapedProductDto scrape(String productUrl);

    /**
     * Checks if any registered extractor supports the given domain/URL.
     */
    boolean supportsDomain(String domainOrUrl);
}
