package com.pricepulse.service.impl;

import com.pricepulse.entity.Product;
import com.pricepulse.entity.User;
import com.pricepulse.service.AlertNotificationService;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;

@Service
public class AlertNotificationServiceImpl implements AlertNotificationService {

    @Override
    public void sendPriceDropAlert(User user, Product product, BigDecimal oldPrice, BigDecimal newPrice) {
        // To be implemented in Phase 3
    }

    @Override
    public void sendTargetThresholdAlert(User user, Product product, BigDecimal targetPrice, BigDecimal currentPrice) {
        // To be implemented in Phase 3
    }
}
