# Running and Testing PricePulse

Comprehensive walkthrough for setting up, configuring, running, and verifying PricePulse locally across Linux, macOS, and Windows PowerShell.

---

## Prerequisites

- **Java Development Kit (JDK) 21 LTS**
- **Apache Maven 3.9+** (or use the bundled `./mvnw` / `mvnw.cmd` wrapper)
- **PostgreSQL 16** (Docker or local instance)
- **Redis 7** (Docker or local instance)
- **Telegram Bot Token** (obtainable via [@BotFather](https://t.me/botfather) in 30 seconds)

---

## 1. Start Infrastructure via Docker

```bash
# 1. Start PostgreSQL 16
docker run --name pricepulse-postgres \
  -e POSTGRES_DB=pricepulse \
  -e POSTGRES_USER=pricepulse \
  -e POSTGRES_PASSWORD=devpassword \
  -p 5432:5432 -d postgres:16

# 2. Start Redis 7
docker run --name pricepulse-redis \
  -p 6379:6379 -d redis:7
```

---

## 2. Configure Environment Variables

### macOS / Linux (Bash / Zsh)
```bash
export DB_URL="jdbc:postgresql://localhost:5432/pricepulse"
export DB_USERNAME="pricepulse"
export DB_PASSWORD="devpassword"
export REDIS_HOST="localhost"
export REDIS_PORT="6379"
export TELEGRAM_BOT_TOKEN="<your-bot-token-from-botfather>"
export TELEGRAM_BOT_USERNAME="PricePulseBot"
```

### Windows (PowerShell)
```powershell
$env:DB_URL="jdbc:postgresql://localhost:5432/pricepulse"
$env:DB_USERNAME="pricepulse"
$env:DB_PASSWORD="devpassword"
$env:REDIS_HOST="localhost"
$env:REDIS_PORT="6379"
$env:TELEGRAM_BOT_TOKEN="<your-bot-token-from-botfather>"
$env:TELEGRAM_BOT_USERNAME="PricePulseBot"
```

---

## 3. Run Automated Tests

The test suite runs with **zero live external network calls** using embedded Redis (`jedis-mock`) and HTTP mock servers (`WireMock`):

```bash
# Linux / macOS
./mvnw test

# Windows PowerShell
.\mvnw.cmd test
```

### What the Test Suite Verifies:
1. **`PriceCacheServiceTest`**: Delta-cache write avoidance (~85% reduction), cache seeding, and `SETNX` distributed lock contention handling.
2. **`RateLimiterServiceTest`**: Sliding Window Log algorithm enforcement using Redis Sorted Sets and atomic Lua script execution.
3. **`PlatformConcurrencyLimiterTest`**: Fair Semaphore concurrency boundary across concurrent virtual threads and eager permit pre-warming.
4. **`WireMockScraperIntegrationTest`**: Jsoup HTML extraction, exponential backoff with full jitter, and Resilience4j 503 circuit-breaker state transitions (`CLOSED` &rarr; `OPEN`).
5. **`PricePulseActuatorEndpointTest`**: Verification that `/actuator` web discovery links expose `/actuator/pricepulse` and return 200 OK with live metrics.
6. **`PricePulseTelegramBotRegistrationTest`**: Verifies token prefix masking, diagnostics, and graceful error handling on registration.

---

## 4. Launch the Application

```bash
# Linux / macOS
./mvnw spring-boot:run

# Windows PowerShell
.\mvnw.cmd spring-boot:run
```

On startup, verify the console logs:
```text
INFO ... PricePulseApplication : Starting PricePulseApplication using Java 21...
INFO ... PlatformConcurrencyLimiter : Initialized concurrency semaphore for platform 'amazon' with 3 permits
INFO ... PlatformConcurrencyLimiter : Initialized concurrency semaphore for platform 'flipkart' with 3 permits
INFO ... PricePulseTelegramBot : Telegram bot token resolved with prefix: 7123... (length: 46)
INFO ... TelegramBotConfig : Initializing TelegramBotsApi with DefaultBotSession for @PricePulseBot...
INFO ... TelegramBotConfig : Telegram bot registered: @PricePulseBot
INFO ... PricePulseApplication : Started PricePulseApplication in 3.4 seconds
```

---

## 5. Interact via Telegram

Open Telegram, search for your bot (`@your_bot_username`), and start tracking:

1. **Send `/start`** — Greets you and displays available commands.
2. **Send `/track <amazon-or-flipkart-url> [target_price]`**:
   - Scrapes current price, stock status, and product title.
   - Creates user and product record in PostgreSQL.
   - Seeds the initial Redis delta-cache entry.
   - Example: `/track https://www.amazon.in/dp/B0CX21CBPJ 15999`
3. **Send `/list`** — Displays all currently tracked subscriptions with current price vs. target threshold.
4. **Send `/status`** — Displays real-time delta-cache performance and circuit breaker states directly in Telegram.
5. **Send `/untrack <product_id>`** — Deactivates tracking for the specified item.

---

## 6. Inspect Observability & Metrics

Query Spring Boot Actuator:

```bash
# View all exposed Actuator links (includes pricepulse)
curl http://localhost:8080/actuator

# View PricePulse custom dashboard
curl http://localhost:8080/actuator/pricepulse

# View Prometheus metrics scrape target
curl http://localhost:8080/actuator/prometheus
```

---

## 7. Common Pitfalls & Troubleshooting

- **Telegram bot produces no log activity**:
  Ensure you are running with Spring Boot 3-compatible `TelegramBotConfig` (included in `com.pricepulse.bot`). If you see a warning that the bot token is `mock_token_for_dev`, make sure your environment variable `$env:TELEGRAM_BOT_TOKEN` was set in the **same terminal window** that launched `mvnw spring-boot:run`.
- **404 on `/actuator/pricepulse`**:
  Ensure `management.endpoints.web.exposure.include` in `application.yml` includes `pricepulse`.
- **Database connection failure**:
  Ensure PostgreSQL is running on port 5432 and the `pricepulse` database has been created (`docker exec -it pricepulse-postgres psql -U pricepulse -d pricepulse`).
- **Redis connection failure**:
  Ensure Redis is running on port 6379 (`redis-cli ping` returns `PONG`).
