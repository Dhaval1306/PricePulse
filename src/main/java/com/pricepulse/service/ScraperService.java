package com.pricepulse.service;

import com.pricepulse.dto.ScrapedProductDto;

public interface ScraperService {
    ScrapedProductDto scrape(String productUrl);
    boolean supportsDomain(String domain);
}
