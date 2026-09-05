package com.pricepulse.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

/**
 * Spring Boot 3 configuration for TelegramBotsApi.
 * <p>
 * Because telegrambots-spring-boot-starter (6.x) relies on legacy META-INF/spring.factories
 * which Spring Boot 3 no longer discovers, this configuration explicitly registers
 * the {@link PricePulseTelegramBot} with {@link TelegramBotsApi} during application startup.
 */
@Configuration
@ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true", matchIfMissing = true)
public class TelegramBotConfig {

    private static final Logger logger = LoggerFactory.getLogger(TelegramBotConfig.class);

    @Bean
    public TelegramBotsApi telegramBotsApi(PricePulseTelegramBot bot) {
        TelegramBotsApi botsApi = null;
        try {
            logger.info("Initializing TelegramBotsApi with DefaultBotSession for @{}...", bot.getBotUsername());
            botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(bot);
            logger.info("Telegram bot registered: @{}", bot.getBotUsername());
        } catch (TelegramApiException e) {
            logger.error("Failed to register Telegram bot @{}: {}", bot.getBotUsername(), e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error during Telegram bot @{} registration: {}", bot.getBotUsername(), e.getMessage(), e);
        }
        return botsApi;
    }
}
