package com.pricepulse.service.impl;

import com.pricepulse.bot.PricePulseTelegramBot;
import com.pricepulse.entity.Product;
import com.pricepulse.entity.User;
import com.pricepulse.service.AlertNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class AlertNotificationServiceImpl implements AlertNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(AlertNotificationServiceImpl.class);

    @Autowired(required = false)
    private PricePulseTelegramBot telegramBot;

    @Override
    public void sendPriceDropAlert(User user, Product product, BigDecimal oldPrice, BigDecimal newPrice) {
        if (oldPrice == null || newPrice == null || oldPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        BigDecimal diff = oldPrice.subtract(newPrice);
        BigDecimal discountPct = diff.divide(oldPrice, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);

        String message = String.format(
                "PRICE DROP ALERT!\n\n" +
                "Product: %s\n\n" +
                "Old Price: %s %s\n" +
                "New Price: %s %s (-%s%%)\n" +
                "Stock: %s\n\n" +
                "URL: %s",
                product.getTitle(),
                product.getCurrency(), oldPrice,
                product.getCurrency(), newPrice, discountPct,
                product.getIsInStock() ? "In Stock" : "Out of Stock",
                product.getUrl()
        );

        deliverMessage(user.getTelegramChatId(), message);
    }

    @Override
    public void sendTargetThresholdAlert(User user, Product product, BigDecimal targetPrice, BigDecimal currentPrice) {
        String message = String.format(
                "TARGET PRICE HIT!\n\n" +
                "Product: %s\n\n" +
                "Your Target: %s %s\n" +
                "Current Price: %s %s\n" +
                "Stock: %s\n\n" +
                "URL: %s",
                product.getTitle(),
                product.getCurrency(), targetPrice,
                product.getCurrency(), currentPrice,
                product.getIsInStock() ? "In Stock" : "Out of Stock",
                product.getUrl()
        );

        deliverMessage(user.getTelegramChatId(), message);
    }

    private void deliverMessage(Long chatId, String text) {
        if (telegramBot != null) {
            telegramBot.sendMessage(chatId, text);
        } else {
            logger.info("TelegramBot disabled/unavailable. Alert log for chat {}:\n{}", chatId, text);
        }
    }
}
