package com.dailyai.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String timezone = "Asia/Kolkata";
    private String adminApiKey = "";
    private String schedulerApiKey = "";
    private Cors cors = new Cors();
    private Rss rss = new Rss();
    private ArticleContent articleContent = new ArticleContent();
    private Ai ai = new Ai();
    private Auth auth = new Auth();

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getAdminApiKey() {
        return adminApiKey;
    }

    public void setAdminApiKey(String adminApiKey) {
        this.adminApiKey = adminApiKey;
    }

    public String getSchedulerApiKey() {
        return schedulerApiKey;
    }

    public void setSchedulerApiKey(String schedulerApiKey) {
        this.schedulerApiKey = schedulerApiKey;
    }

    public Cors getCors() {
        return cors;
    }

    public void setCors(Cors cors) {
        this.cors = cors;
    }

    public Rss getRss() {
        return rss;
    }

    public void setRss(Rss rss) {
        this.rss = rss;
    }

    public ArticleContent getArticleContent() { return articleContent; }
    public void setArticleContent(ArticleContent articleContent) { this.articleContent = articleContent; }

    public static class ArticleContent {
        // Fetching publisher pages is opt-in per domain so terms can be reviewed.
        private List<String> allowedHosts = new ArrayList<>();
        private int timeoutMs = 5000;
        private int maxCharacters = 30000;

        public List<String> getAllowedHosts() { return allowedHosts; }
        public void setAllowedHosts(List<String> allowedHosts) { this.allowedHosts = allowedHosts; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxCharacters() { return maxCharacters; }
        public void setMaxCharacters(int maxCharacters) { this.maxCharacters = maxCharacters; }
    }

    public Ai getAi() {
        return ai;
    }

    public void setAi(Ai ai) {
        this.ai = ai;
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth;
    }

    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:5173"));

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    public static class Rss {
        private String userAgent = "DailyAiAssistant/1.0 (+https://example.com/daily-ai-assistant)";
        private int connectTimeoutMs = 8000;
        private int readTimeoutMs = 12000;
        private int maxItemsPerFeed = 30;
        private boolean fetchOnStartup = true;
        private List<Feed> feeds = new ArrayList<>();

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        public int getMaxItemsPerFeed() {
            return maxItemsPerFeed;
        }

        public void setMaxItemsPerFeed(int maxItemsPerFeed) {
            this.maxItemsPerFeed = maxItemsPerFeed;
        }

        public boolean isFetchOnStartup() {
            return fetchOnStartup;
        }

        public void setFetchOnStartup(boolean fetchOnStartup) {
            this.fetchOnStartup = fetchOnStartup;
        }

        public List<Feed> getFeeds() {
            return feeds;
        }

        public void setFeeds(List<Feed> feeds) {
            this.feeds = feeds;
        }
    }

    public static class Feed {
        private String name;
        private String url;
        private String category;
        private boolean allowAiContent;
        private String region = "UNKNOWN";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public boolean isAllowAiContent() { return allowAiContent; }
        public void setAllowAiContent(boolean allowAiContent) { this.allowAiContent = allowAiContent; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
    }

    public static class Auth {
        private String bootstrapEmail = "";
        private String bootstrapPassword = "";
        private String bootstrapRole = "USER";
        private int maxLoginAttempts = 5;
        private int loginWindowMinutes = 15;

        public String getBootstrapEmail() {
            return bootstrapEmail;
        }

        public void setBootstrapEmail(String bootstrapEmail) {
            this.bootstrapEmail = bootstrapEmail;
        }

        public String getBootstrapPassword() {
            return bootstrapPassword;
        }

        public void setBootstrapPassword(String bootstrapPassword) {
            this.bootstrapPassword = bootstrapPassword;
        }

        public String getBootstrapRole() {
            return bootstrapRole;
        }

        public void setBootstrapRole(String bootstrapRole) {
            this.bootstrapRole = bootstrapRole;
        }

        public int getMaxLoginAttempts() {
            return maxLoginAttempts;
        }

        public void setMaxLoginAttempts(int maxLoginAttempts) {
            this.maxLoginAttempts = maxLoginAttempts;
        }

        public int getLoginWindowMinutes() {
            return loginWindowMinutes;
        }

        public void setLoginWindowMinutes(int loginWindowMinutes) {
            this.loginWindowMinutes = loginWindowMinutes;
        }
    }

    public static class Ai {
        private boolean enabled = false;
        private String provider = "groq";
        private String baseUrl = "https://api.groq.com/openai/v1";
        private String apiKey = "";
        private String model = "openai/gpt-oss-20b";
        private int timeoutMs = 30000;
        private int maxAttempts = 2;
        private int maxCompletionTokens = 4096;
        /** Approximate source-token budget; the client estimates at four characters per token. */
        private int maxSourceTokens = 4500;
        private long retryDelayMs = 1000;
        private int failureThreshold = 2;
        private long cooldownMs = 300000;
        /** Hard cap on provider requests per local day. Zero disables the cap. */
        private int maxCallsPerDay = 100;
        /** Do not retry an article whose AI attempt already failed inside this window. */
        private int retryAfterMinutes = 360;

        public boolean isEnabled() {
            return enabled && apiKey != null && !apiKey.isBlank();
        }

        public boolean isEnabledConfigured() { return enabled; }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getMaxCompletionTokens() { return maxCompletionTokens; }
        public void setMaxCompletionTokens(int maxCompletionTokens) { this.maxCompletionTokens = maxCompletionTokens; }

        public int getMaxSourceTokens() { return maxSourceTokens; }
        public void setMaxSourceTokens(int maxSourceTokens) { this.maxSourceTokens = maxSourceTokens; }

        public long getRetryDelayMs() {
            return retryDelayMs;
        }

        public void setRetryDelayMs(long retryDelayMs) {
            this.retryDelayMs = retryDelayMs;
        }

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public long getCooldownMs() {
            return cooldownMs;
        }

        public void setCooldownMs(long cooldownMs) {
            this.cooldownMs = cooldownMs;
        }

        public int getMaxCallsPerDay() {
            return maxCallsPerDay;
        }

        public void setMaxCallsPerDay(int maxCallsPerDay) {
            this.maxCallsPerDay = maxCallsPerDay;
        }

        public int getRetryAfterMinutes() {
            return retryAfterMinutes;
        }

        public void setRetryAfterMinutes(int retryAfterMinutes) {
            this.retryAfterMinutes = retryAfterMinutes;
        }
    }
}
