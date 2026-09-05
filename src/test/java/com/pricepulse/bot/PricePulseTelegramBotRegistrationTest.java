package com.pricepulse.bot;

import com.pricepulse.concurrency.PlatformConcurrencyLimiter;
import com.pricepulse.repository.ProductRepository;
import com.pricepulse.repository.UserProductSubscriptionRepository;
import com.pricepulse.repository.UserRepository;
import com.pricepulse.service.PriceCacheService;
import com.pricepulse.service.ScraperService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.telegram.telegrambots.meta.TelegramBotsApi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PricePulseTelegramBotRegistrationTest {

    private PricePulseTelegramBot createBotWithToken(String token) {
        return new PricePulseTelegramBot(
                token,
                mock(UserRepository.class),
                mock(ProductRepository.class),
                mock(UserProductSubscriptionRepository.class),
                mock(ScraperService.class),
                mock(PriceCacheService.class),
                new SimpleMeterRegistry(),
                mock(CircuitBreakerRegistry.class),
                mock(PlatformConcurrencyLimiter.class)
        );
    }

    @Test
    @DisplayName("Should mask bot token prefix safely without exposing full secret")
    void testMaskedTokenFormat() {
        PricePulseTelegramBot bot = createBotWithToken("7123456789:AAF_mockSecretTokenPartXYZ");
        assertEquals("7123...", bot.getMaskedToken(),
                "Masked token should only show first 4 characters followed by ellipsis");

        PricePulseTelegramBot devBot = createBotWithToken("mock_token_for_dev");
        assertEquals("mock_token_for_dev", devBot.getMaskedToken());

        PricePulseTelegramBot emptyBot = createBotWithToken("");
        assertEquals("<EMPTY>", emptyBot.getMaskedToken());

        PricePulseTelegramBot nullBot = createBotWithToken(null);
        assertEquals("<EMPTY>", nullBot.getMaskedToken());
    }

    @Test
    @DisplayName("Should log diagnostics during init() without throwing exceptions")
    void testInitDiagnosticsExecution() {
        PricePulseTelegramBot realPrefixBot = createBotWithToken("7123456789:ABC_TEST_TOKEN");
        assertDoesNotThrow(realPrefixBot::init);

        PricePulseTelegramBot devBot = createBotWithToken("mock_token_for_dev");
        assertDoesNotThrow(devBot::init);

        PricePulseTelegramBot emptyBot = createBotWithToken("");
        assertDoesNotThrow(emptyBot::init);
    }

    @Test
    @DisplayName("Should catch and handle registration errors gracefully in TelegramBotConfig")
    void testRegistrationHandlesExceptionsGracefully() {
        TelegramBotConfig config = new TelegramBotConfig();
        // A bot with mock token will fail clearWebhook network call to Telegram API
        PricePulseTelegramBot bot = createBotWithToken("mock_token_for_dev");

        // telegramBotsApi method should catch TelegramApiException and not throw
        assertDoesNotThrow(() -> {
            TelegramBotsApi api = config.telegramBotsApi(bot);
            // Registration failure was caught and logged with error level
        });
    }
}
