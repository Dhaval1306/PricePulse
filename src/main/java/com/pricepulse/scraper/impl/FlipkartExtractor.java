package com.pricepulse.scraper.impl;

import com.pricepulse.dto.ScrapedProductDto;
import com.pricepulse.scraper.PlatformExtractor;
import com.pricepulse.scraper.ScraperUtils;
import io.micrometer.core.instrument.MeterRegistry;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class FlipkartExtractor implements PlatformExtractor {

    private static final Logger logger = LoggerFactory.getLogger(FlipkartExtractor.class);

    // Note: robots.txt and site Terms of Service (ToS) parsing are out-of-scope for this educational portfolio project.

    private final MeterRegistry meterRegistry;

    // Ordered list of price fallback selectors for Flipkart
    private static final String[] PRICE_SELECTORS = {
            "div.Nx9bqj",
            "div._30jeq3",
            "div._16Jk6d",
            "div.hl05eU div"
    };

    // Ordered list of title fallback selectors
    private static final String[] TITLE_SELECTORS = {
            "span.VU-ZEz",
            "span.B_NuCI",
            "h1.yhB1nd",
            "span._35KyD6"
    };

    public FlipkartExtractor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean supports(String domainOrUrl) {
        if (domainOrUrl == null) return false;
        String lower = domainOrUrl.toLowerCase();
        return lower.contains("flipkart.com") || lower.contains("fkrt.it");
    }

    @Override
    public String getPlatformName() {
        return "flipkart";
    }

    @Override
    public ScrapedProductDto extract(Document doc, String sourceUrl) {
        String title = extractTitle(doc);
        BigDecimal price = extractPrice(doc);
        boolean inStock = extractStock(doc);
        String imageUrl = extractImage(doc);

        return new ScrapedProductDto(
                title,
                price,
                "INR",
                inStock,
                imageUrl,
                getPlatformName()
        );
    }

    private String extractTitle(Document doc) {
        for (String selector : TITLE_SELECTORS) {
            Element element = doc.selectFirst(selector);
            if (element != null && !element.text().isBlank()) {
                return element.text().trim();
            }
        }
        meterRegistry.counter("pricepulse.scraper.dom.failures", "platform", "flipkart", "field", "title").increment();
        logger.warn("Selector Rot Alert: FlipkartExtractor failed to extract title using fallback selectors.");
        return "Unknown Flipkart Product";
    }

    private BigDecimal extractPrice(Document doc) {
        for (String selector : PRICE_SELECTORS) {
            Element element = doc.selectFirst(selector);
            if (element != null && !element.text().isBlank()) {
                BigDecimal price = ScraperUtils.parsePrice(element.text());
                if (price != null) {
                    return price;
                }
            }
        }
        meterRegistry.counter("pricepulse.scraper.dom.failures", "platform", "flipkart", "field", "price").increment();
        logger.warn("Selector Rot Alert: FlipkartExtractor failed to extract price using fallback selectors.");
        return BigDecimal.ZERO;
    }

    private boolean extractStock(Document doc) {
        Element outOfStockElement = doc.selectFirst("div._16FRp0, div._1V3wBu");
        if (outOfStockElement != null && outOfStockElement.text().toLowerCase().contains("sold out")) {
            return false;
        }
        return true;
    }

    private String extractImage(Document doc) {
        Element img = doc.selectFirst("img._396cs4._2amPTt, img.DByuf4");
        return (img != null && img.hasAttr("src")) ? img.attr("src") : null;
    }
}
