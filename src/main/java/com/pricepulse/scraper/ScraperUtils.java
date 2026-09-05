package com.pricepulse.scraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;

public final class ScraperUtils {

    private static final Logger logger = LoggerFactory.getLogger(ScraperUtils.class);

    private ScraperUtils() {}

    /**
     * Parses a raw price string (e.g., "₹1,499.00", "$29.99", "1.499,00") into a clean BigDecimal.
     */
    public static BigDecimal parsePrice(String rawPrice) {
        if (rawPrice == null || rawPrice.isBlank()) {
            return null;
        }

        try {
            // Remove currency symbols, commas, non-breaking spaces, and whitespace
            String cleaned = rawPrice.replaceAll("[^0-9.]", "").trim();
            // In case of multiple dots (e.g. 1.299.00), retain only the decimal part
            int firstDot = cleaned.indexOf('.');
            int lastDot = cleaned.lastIndexOf('.');
            if (firstDot != -1 && firstDot != lastDot) {
                String integerPart = cleaned.substring(0, lastDot).replace(".", "");
                String decimalPart = cleaned.substring(lastDot);
                cleaned = integerPart + decimalPart;
            }
            if (cleaned.isEmpty()) {
                return null;
            }
            return new BigDecimal(cleaned);
        } catch (Exception e) {
            logger.warn("Failed to parse raw price string '{}': {}", rawPrice, e.getMessage());
            return null;
        }
    }

    /**
     * Extracts host/domain from a URL (e.g. "www.amazon.in" -> "amazon.in").
     */
    public static String extractDomain(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) return "unknown";
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
