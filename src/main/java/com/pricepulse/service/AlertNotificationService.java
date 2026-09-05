package com.pricepulse.service;

import com.pricepulse.entity.Product;
import com.pricepulse.entity.User;
import java.math.BigDecimal;

public interface AlertNotificationService {
    void sendPriceDropAlert(User user, Product product, BigDecimal oldPrice, BigDecimal newPrice);
    void sendTargetThresholdAlert(User user, Product product, BigDecimal targetPrice, BigDecimal currentPrice);
}
