package com.pricepulse.scraper;

import com.pricepulse.dto.ScrapedProductDto;
import org.jsoup.nodes.Document;

public interface PlatformExtractor {

    /**
     * Determines if this extractor supports the given domain/URL.
     */
    boolean supports(String domainOrUrl);

    /**
     * Extracts structured product data from the Jsoup DOM document.
     *
     * @param document Jsoup parsed HTML document
     * @param sourceUrl the product URL being scraped
     * @return ScrapedProductDto containing title, price, currency, stock status, image
     */
    ScrapedProductDto extract(Document document, String sourceUrl);

    /**
     * Unique platform identifier (e.g. "amazon", "flipkart").
     */
    String getPlatformName();
}
