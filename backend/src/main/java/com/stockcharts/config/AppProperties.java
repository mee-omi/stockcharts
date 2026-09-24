package com.stockcharts.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Binds the {@code stockcharts.*} settings from application.yml. */
@ConfigurationProperties(prefix = "stockcharts")
public class AppProperties {

    private String dataServiceUrl = "http://localhost:8000";
    private List<String> liveSymbols = List.of("RELIANCE", "TCS");
    private int backfillDays = 5;
    /** Run live tick ingestion on this instance (disable on all but one replica in cluster mode). */
    private boolean liveEnabled = true;
    /** Cap on symbols added to the live stream by client subscriptions. */
    private int liveMaxSymbols = 100;
    private final Screener screener = new Screener();
    private final Alerts alerts = new Alerts();
    private final RateLimit rateLimit = new RateLimit();
    private final Cluster cluster = new Cluster();
    private final HistorySync historySync = new HistorySync();
    /** Max wait for a data-service response; chunked multi-year broker downloads can be slow. */
    private java.time.Duration dataServiceTimeout = java.time.Duration.ofMinutes(3);

    /** Periodic download of broker history into the local candle store. */
    public static class HistorySync {
        private boolean enabled = true;
        /** Run shortly after startup (after the instrument sync). */
        private boolean onStartup = true;
        /** Also sync when the mock provider is active (it generates data on demand anyway). */
        private boolean includeMock = false;
        /** Timeframe:lookback-days pairs, e.g. "1d:1825,1m:30". */
        private String timeframes = "1d:1825,15m:120,1m:30";
        /** Index universes whose constituents are always synced (watchlists are always included). */
        private List<String> indices = List.of("NIFTY50");
        private int maxSymbols = 300;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isOnStartup() {
            return onStartup;
        }

        public void setOnStartup(boolean onStartup) {
            this.onStartup = onStartup;
        }

        public boolean isIncludeMock() {
            return includeMock;
        }

        public void setIncludeMock(boolean includeMock) {
            this.includeMock = includeMock;
        }

        public String getTimeframes() {
            return timeframes;
        }

        public void setTimeframes(String timeframes) {
            this.timeframes = timeframes;
        }

        public List<String> getIndices() {
            return indices;
        }

        public void setIndices(List<String> indices) {
            this.indices = indices;
        }

        public int getMaxSymbols() {
            return maxSymbols;
        }

        public void setMaxSymbols(int maxSymbols) {
            this.maxSymbols = maxSymbols;
        }
    }

    public HistorySync getHistorySync() {
        return historySync;
    }

    public java.time.Duration getDataServiceTimeout() {
        return dataServiceTimeout;
    }

    public void setDataServiceTimeout(java.time.Duration dataServiceTimeout) {
        this.dataServiceTimeout = dataServiceTimeout;
    }

    /** Alert evaluation and notification channels. */
    public static class Alerts {
        /** Telegram bot token + chat id; both required to enable Telegram delivery. */
        private String telegramBotToken;
        private String telegramChatId;
        /** Optional URL that receives a JSON POST for every triggered alert. */
        private String webhookUrl;

        public String getTelegramBotToken() {
            return telegramBotToken;
        }

        public void setTelegramBotToken(String telegramBotToken) {
            this.telegramBotToken = telegramBotToken;
        }

        public String getTelegramChatId() {
            return telegramChatId;
        }

        public void setTelegramChatId(String telegramChatId) {
            this.telegramChatId = telegramChatId;
        }

        public String getWebhookUrl() {
            return webhookUrl;
        }

        public void setWebhookUrl(String webhookUrl) {
            this.webhookUrl = webhookUrl;
        }
    }

    /** Per-client request limits (requests per minute). */
    public static class RateLimit {
        private boolean enabled = true;
        private int requestsPerMinute = 600;
        /** Order placement / broker switching. */
        private int tradingPerMinute = 60;
        /** CPU-heavy endpoints: screener runs and backtests. */
        private int heavyPerMinute = 30;
        /** Use X-Forwarded-For as the client address (only behind a trusted proxy). */
        private boolean trustForwardedFor = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getRequestsPerMinute() {
            return requestsPerMinute;
        }

        public void setRequestsPerMinute(int requestsPerMinute) {
            this.requestsPerMinute = requestsPerMinute;
        }

        public int getTradingPerMinute() {
            return tradingPerMinute;
        }

        public void setTradingPerMinute(int tradingPerMinute) {
            this.tradingPerMinute = tradingPerMinute;
        }

        public int getHeavyPerMinute() {
            return heavyPerMinute;
        }

        public void setHeavyPerMinute(int heavyPerMinute) {
            this.heavyPerMinute = heavyPerMinute;
        }

        public boolean isTrustForwardedFor() {
            return trustForwardedFor;
        }

        public void setTrustForwardedFor(boolean trustForwardedFor) {
            this.trustForwardedFor = trustForwardedFor;
        }
    }

    /** Settings for running more than one backend replica. */
    public static class Cluster {
        /** Guard scheduled jobs with ShedLock (needs the shedlock table from Flyway V8). */
        private boolean enabled = false;
        /** Relay STOMP through an external broker (e.g. RabbitMQ) instead of the in-memory one. */
        private boolean brokerRelayEnabled = false;
        private String brokerRelayHost = "localhost";
        private int brokerRelayPort = 61613;
        private String brokerRelayLogin = "guest";
        private String brokerRelayPasscode = "guest";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isBrokerRelayEnabled() {
            return brokerRelayEnabled;
        }

        public void setBrokerRelayEnabled(boolean brokerRelayEnabled) {
            this.brokerRelayEnabled = brokerRelayEnabled;
        }

        public String getBrokerRelayHost() {
            return brokerRelayHost;
        }

        public void setBrokerRelayHost(String brokerRelayHost) {
            this.brokerRelayHost = brokerRelayHost;
        }

        public int getBrokerRelayPort() {
            return brokerRelayPort;
        }

        public void setBrokerRelayPort(int brokerRelayPort) {
            this.brokerRelayPort = brokerRelayPort;
        }

        public String getBrokerRelayLogin() {
            return brokerRelayLogin;
        }

        public void setBrokerRelayLogin(String brokerRelayLogin) {
            this.brokerRelayLogin = brokerRelayLogin;
        }

        public String getBrokerRelayPasscode() {
            return brokerRelayPasscode;
        }

        public void setBrokerRelayPasscode(String brokerRelayPasscode) {
            this.brokerRelayPasscode = brokerRelayPasscode;
        }
    }

    public Alerts getAlerts() {
        return alerts;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Cluster getCluster() {
        return cluster;
    }

    public boolean isLiveEnabled() {
        return liveEnabled;
    }

    public void setLiveEnabled(boolean liveEnabled) {
        this.liveEnabled = liveEnabled;
    }

    public int getLiveMaxSymbols() {
        return liveMaxSymbols;
    }

    public void setLiveMaxSymbols(int liveMaxSymbols) {
        this.liveMaxSymbols = liveMaxSymbols;
    }

    /** Screener tuning (bounds the heavy "all NSE" scans). */
    public static class Screener {
        private int maxUniverse = 500;
        private int parallelism = 8;
        private int resultLimit = 200;

        public int getMaxUniverse() {
            return maxUniverse;
        }

        public void setMaxUniverse(int maxUniverse) {
            this.maxUniverse = maxUniverse;
        }

        public int getParallelism() {
            return parallelism;
        }

        public void setParallelism(int parallelism) {
            this.parallelism = parallelism;
        }

        public int getResultLimit() {
            return resultLimit;
        }

        public void setResultLimit(int resultLimit) {
            this.resultLimit = resultLimit;
        }
    }

    public Screener getScreener() {
        return screener;
    }

    public String getDataServiceUrl() {
        return dataServiceUrl;
    }

    public void setDataServiceUrl(String dataServiceUrl) {
        this.dataServiceUrl = dataServiceUrl;
    }

    public List<String> getLiveSymbols() {
        return liveSymbols;
    }

    public void setLiveSymbols(List<String> liveSymbols) {
        this.liveSymbols = liveSymbols;
    }

    public int getBackfillDays() {
        return backfillDays;
    }

    public void setBackfillDays(int backfillDays) {
        this.backfillDays = backfillDays;
    }
}
