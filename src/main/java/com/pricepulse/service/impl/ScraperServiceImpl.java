package com.pricepulse.service.impl;

import com.pricepulse.dto.ScrapedProductDto;
import com.pricepulse.service.ScraperService;
import org.springframework.stereotype.Service;

@Service
public class ScraperServiceImpl implements ScraperService {

    @Override
    public ScrapedProductDto scrape(String productUrl) {
        // To be implemented in Phase 2 Module 2
        return null;
    }

    @Override
    public boolean supportsDomain(String domain) {
        // To be implemented in Phase 2 Module 2
        return true;
    }
}
