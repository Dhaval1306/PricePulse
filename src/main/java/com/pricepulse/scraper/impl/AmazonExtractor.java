package com.pricepulse.scraper.impl;

import com.pricepulse.dto.ScrapedProductDto;
import com.pricepulse.scraper.PlatformExtractor;
import com.pricepulse.scraper.ScraperUtils;
import io.micrometer.core.instrument.MeterRegistry;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class AmazonExtractor implements PlatformExtractor {

    private static final Logger logger = LoggerFactory.getLogger(AmazonExtractor.class);

    // Note: robots.txt and site Terms of Service (ToS) parsing are out-of-scope for this educational portfolio project.

    private final MeterRegistry meterRegistry;

    // Ordered list of price fallback selectors for Amazon
    private static final String[] PRICE_SELECTORS = {
            "span.a-price-whole",
            "span.apexPriceToPay span.a-offscreen",
            "span.priceToPay span.a-offscreen",
            "span#priceblock_ourprice",
            "span#priceblock_dealprice",
            "span.a-color-price"
    };

    // Ordered list of title fallback selectors
    private static final String[] TITLE_SELECTORS = {
            "span#productTitle",
            "h1#title span",
            "#title"
    };

    public AmazonExtractor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean supports(String domainOrUrl) {
        if (domainOrUrl == null) return false;
        String lower = domainOrUrl.toLowerCase();
        return lower.contains("amazon.in") || lower.contains("amazon.com") || lower.contains("amzn.to");
    }

    @Override
    public String getPlatformName() {
        return "amazon";
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
        // Increment DOM extraction failure metric for title (selector rot observability)
        meterRegistry.counter("pricepulse.scraper.dom.failures", "platform", "amazon", "field", "title").increment();
        logger.warn("Selector Rot Alert: AmazonExtractor failed to extract title using fallback selectors.");
        return "Unknown Product Title";
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
        // Increment DOM extraction failure metric for price (selector rot observability)
        meterRegistry.counter("pricepulse.scraper.dom.failures", "platform", "amazon", "field", "price").increment();
        logger.warn("Selector Rot Alert: AmazonExtractor failed to extract price using fallback selectors.");
        return BigDecimal.ZERO;
    }

    private boolean extractStock(Document doc) {
        Element availElement = doc.selectFirst("div#availability span");
        if (availElement != null) {
            String text = availElement.text().toLowerCase();
            if (text.contains("currently unavailable") || text.contains("out of stock")) {
                return false;
            }
        }
        return true;
    }

    private String extractImage(Document doc) {
        Element img = doc.selectFirst("img#landingImage");
        if (img != null && img.hasAttr("src")) {
            return img.attr("src");
        }
        Element altImg = doc.selectFirst("div#imgTagWrapperId img");
        return (altImg != null) ? altImg.attr("src") : null;
    }
}
