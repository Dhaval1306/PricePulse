package com.pricepulse.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
public class PricePulseTelegramBot extends TelegramLongPollingBot {

    private static final Logger logger = LoggerFactory.getLogger(PricePulseTelegramBot.class);

    @Value("${telegram.bot.username:PricePulseBot}")
    private String botUsername;

    public PricePulseTelegramBot(@Value("${telegram.bot.token:}") String botToken) {
        super(botToken);
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        // To be implemented in Phase 3
        logger.debug("Telegram update received: {}", update.getUpdateId());
    }
}
