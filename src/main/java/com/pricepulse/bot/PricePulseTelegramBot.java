package com.pricepulse.bot;

import com.pricepulse.concurrency.PlatformConcurrencyLimiter;
import com.pricepulse.dto.ScrapedProductDto;
import com.pricepulse.entity.Product;
import com.pricepulse.entity.User;
import com.pricepulse.entity.UserProductSubscription;
import com.pricepulse.repository.ProductRepository;
import com.pricepulse.repository.UserProductSubscriptionRepository;
import com.pricepulse.repository.UserRepository;
import com.pricepulse.service.PriceCacheService;
import com.pricepulse.service.ScraperService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true", matchIfMissing = true)
public class PricePulseTelegramBot extends TelegramLongPollingBot {

    private static final Logger logger = LoggerFactory.getLogger(PricePulseTelegramBot.class);

    @Value("${telegram.bot.username:PricePulseBot}")
    private String botUsername;

    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final UserProductSubscriptionRepository subscriptionRepository;
    private final ScraperService scraperService;
    private final PriceCacheService priceCacheService;
    private final MeterRegistry meterRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final PlatformConcurrencyLimiter concurrencyLimiter;

    public PricePulseTelegramBot(
            @Value("${telegram.bot.token:}") String botToken,
            UserRepository userRepository,
            ProductRepository productRepository,
            UserProductSubscriptionRepository subscriptionRepository,
            ScraperService scraperService,
            PriceCacheService priceCacheService,
            MeterRegistry meterRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry,
            PlatformConcurrencyLimiter concurrencyLimiter) {
        super(botToken);
        this.userRepository = userRepository;
        this.productRepository = productRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.scraperService = scraperService;
        this.priceCacheService = priceCacheService;
        this.meterRegistry = meterRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            Message message = update.getMessage();
            Long chatId = message.getChatId();
            String text = message.getText().trim();
            String username = message.getFrom().getUserName();

            User user = getOrCreateUser(chatId, username);

            try {
                if (text.startsWith("/start")) {
                    handleStartCommand(chatId);
                } else if (text.startsWith("/track")) {
                    handleTrackCommand(chatId, user, text);
                } else if (text.startsWith("/untrack")) {
                    handleUntrackCommand(chatId, user, text);
                } else if (text.startsWith("/list")) {
                    handleListCommand(chatId, user);
                } else if (text.startsWith("/status")) {
                    handleStatusCommand(chatId);
                } else {
                    sendMessage(chatId, "Unknown command. Type /start to see available commands.");
                }
            } catch (Exception e) {
                logger.error("Error processing command '{}' for chat {}: {}", text, chatId, e.getMessage());
                sendMessage(chatId, "An error occurred while processing your request: " + e.getMessage());
            }
        }
    }

    private void handleStartCommand(Long chatId) {
        String welcome = "Welcome to PricePulse!\n" +
                "Your automated e-commerce price monitor & resilient alert engine.\n\n" +
                "Available Commands:\n" +
                "• /track <url> [target_price] — Start tracking a product\n" +
                "• /untrack <product_id> — Stop tracking a product\n" +
                "• /list — View your active tracked items\n" +
                "• /status — View system health & write-avoidance metrics\n\n" +
                "Example:\n" +
                "/track https://www.amazon.in/dp/B0CX21CBPJ 15999";
        sendMessage(chatId, welcome);
    }

    private void handleTrackCommand(Long chatId, User user, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length < 2) {
            sendMessage(chatId, "Usage: /track <product_url> [target_price]");
            return;
        }

        String url = parts[1].trim();
        BigDecimal targetPrice = null;
        if (parts.length >= 3) {
            try {
                targetPrice = new BigDecimal(parts[2].trim());
            } catch (Exception ignored) {}
        }

        if (!scraperService.supportsDomain(url)) {
            sendMessage(chatId, "Unsupported platform. Currently supporting Amazon and Flipkart.");
            return;
        }

        sendMessage(chatId, "Fetching initial product details from the platform...");

        try {
            ScrapedProductDto scraped = scraperService.scrape(url);
            if (scraped == null || scraped.price() == null) {
                sendMessage(chatId, "Failed to retrieve price details from this URL.");
                return;
            }

            final BigDecimal initialPrice = scraped.price();
            final String title = scraped.title();
            final String platform = scraped.platform();
            final String imageUrl = scraped.imageUrl();
            final String currency = scraped.currency();
            final boolean inStock = scraped.isInStock();

            Product product = productRepository.findByUrl(url).orElseGet(() -> {
                Product p = new Product(url, platform, title, initialPrice);
                p.setImageUrl(imageUrl);
                p.setCurrency(currency);
                p.setIsInStock(inStock);
                return productRepository.save(p);
            });

            priceCacheService.processPriceUpdate(product.getId(), initialPrice, inStock);

            Optional<UserProductSubscription> existingSub = subscriptionRepository.findByUserAndProduct(user, product);
            UserProductSubscription sub;
            if (existingSub.isPresent()) {
                sub = existingSub.get();
                sub.setIsActive(true);
                sub.setTargetPrice(targetPrice);
            } else {
                sub = new UserProductSubscription(user, product, targetPrice);
            }
            subscriptionRepository.save(sub);

            String response = String.format(
                    "Tracking Started!\n\n" +
                    "Product: %s\n" +
                    "ID: %d\n" +
                    "Current Price: %s %s\n" +
                    "Target Alert Price: %s\n" +
                    "Stock Status: %s\n\n" +
                    "You will receive an instant alert when the price drops!",
                    product.getTitle(),
                    product.getId(),
                    product.getCurrency(), product.getCurrentPrice(),
                    targetPrice != null ? product.getCurrency() + " " + targetPrice : "Any price drop",
                    product.getIsInStock() ? "In Stock" : "Out of Stock"
            );
            sendMessage(chatId, response);

        } catch (Exception e) {
            logger.error("Failed to track product {}: {}", url, e.getMessage());
            sendMessage(chatId, "Scraping error: " + e.getMessage());
        }
    }

    private void handleUntrackCommand(Long chatId, User user, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length < 2) {
            sendMessage(chatId, "Usage: /untrack <product_id>");
            return;
        }

        try {
            Long productId = Long.parseLong(parts[1].trim());
            Product product = productRepository.findById(productId).orElse(null);
            if (product == null) {
                sendMessage(chatId, "Product not found with ID: " + productId);
                return;
            }

            Optional<UserProductSubscription> subOpt = subscriptionRepository.findByUserAndProduct(user, product);
            if (subOpt.isPresent()) {
                UserProductSubscription sub = subOpt.get();
                sub.setIsActive(false);
                subscriptionRepository.save(sub);
                sendMessage(chatId, "Successfully stopped tracking " + product.getTitle());
            } else {
                sendMessage(chatId, "You are not tracking product #" + productId);
            }
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Invalid product ID. Please provide a numeric ID.");
        }
    }

    private void handleListCommand(Long chatId, User user) {
        List<UserProductSubscription> subs = subscriptionRepository.findByUserAndIsActiveTrue(user);
        if (subs.isEmpty()) {
            sendMessage(chatId, "You are not tracking any products yet. Use /track <url> to begin!");
            return;
        }

        StringBuilder sb = new StringBuilder("Your Tracked Products:\n\n");
        int index = 1;
        for (UserProductSubscription sub : subs) {
            Product p = sub.getProduct();
            sb.append(String.format(
                    "%d. %s\n   ID: %d | Price: %s %s\n   Target: %s | %s\n   URL: %s\n\n",
                    index++,
                    p.getTitle(),
                    p.getId(),
                    p.getCurrency(), p.getCurrentPrice(),
                    sub.getTargetPrice() != null ? p.getCurrency() + " " + sub.getTargetPrice() : "Any drop",
                    p.getIsInStock() ? "In Stock" : "Out of Stock",
                    p.getUrl()
            ));
        }
        sendMessage(chatId, sb.toString());
    }

    private void handleStatusCommand(Long chatId) {
        Counter totalScrapes = meterRegistry.find("pricepulse.scrapes.total").counter();
        Counter writesAvoided = meterRegistry.find("pricepulse.db.writes.avoided").counter();
        Counter writesCommitted = meterRegistry.find("pricepulse.db.writes.committed").counter();

        double total = (totalScrapes != null) ? totalScrapes.count() : 0;
        double avoided = (writesAvoided != null) ? writesAvoided.count() : 0;
        double committed = (writesCommitted != null) ? writesCommitted.count() : 0;
        double ratio = (total > 0) ? (avoided / total) * 100.0 : 0.0;

        CircuitBreaker amazonCb = circuitBreakerRegistry.find("amazon").orElse(null);
        CircuitBreaker flipkartCb = circuitBreakerRegistry.find("flipkart").orElse(null);

        String status = String.format(
                "PricePulse Engine Health & Metrics\n\n" +
                "Delta-Cache Performance:\n" +
                "• Total Scrapes Processed: %.0f\n" +
                "• DB Writes Avoided: %.0f\n" +
                "• DB Writes Committed: %.0f\n" +
                "• Write-Reduction Rate: %.1f%% (Target: ~85%%)\n\n" +
                "Circuit Breakers:\n" +
                "• Amazon: %s\n" +
                "• Flipkart: %s\n\n" +
                "Concurrency Permits Available:\n" +
                "• Amazon: %d\n" +
                "• Flipkart: %d\n\n" +
                "Threads: Java 21 Virtual Threads (Loom) Active",
                total, avoided, committed, ratio,
                amazonCb != null ? amazonCb.getState() : "CLOSED",
                flipkartCb != null ? flipkartCb.getState() : "CLOSED",
                concurrencyLimiter.getAvailablePermits("amazon"),
                concurrencyLimiter.getAvailablePermits("flipkart")
        );
        sendMessage(chatId, status);
    }

    public void sendMessage(Long chatId, String text) {
        try {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .disableWebPagePreview(true)
                    .build();
            execute(message);
        } catch (TelegramApiException e) {
            logger.error("Failed to send Telegram message to chat {}: {}", chatId, e.getMessage());
        }
    }

    private User getOrCreateUser(Long chatId, String username) {
        return userRepository.findByTelegramChatId(chatId).orElseGet(() ->
                userRepository.save(new User(chatId, username))
        );
    }
}
