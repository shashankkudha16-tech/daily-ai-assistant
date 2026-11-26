package com.dailyai.assistant.integration;

import com.dailyai.assistant.config.AppProperties;
import com.dailyai.assistant.entity.Article;
import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.Timeout;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Fetches only approved public publisher pages, with DNS pinned to the checked addresses. */
@Component
public class PublisherArticleExtractor {
    private static final Logger log = LoggerFactory.getLogger(PublisherArticleExtractor.class);
    private static final String USER_AGENT = "DailyAIAssistantBot";
    private static final int MAX_RESPONSE_BYTES = 1_500_000;
    private static final int MAX_ROBOTS_BYTES = 512_000;
    private static final Pattern AI_RESTRICTION = Pattern.compile("(?i)(^|[^a-z0-9_-])(noai|no-llm)([^a-z0-9_-]|$)");
    private static final List<String> BLOCKED_IPV4_CIDRS = List.of(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
            "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16",
            "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4");

    @FunctionalInterface
    interface AddressLookup {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final AppProperties.ArticleContent properties;
    private final AddressLookup addressLookup;

    @Autowired
    public PublisherArticleExtractor(AppProperties properties) {
        this(properties.getArticleContent(), InetAddress::getAllByName);
    }

    PublisherArticleExtractor(AppProperties.ArticleContent properties, AddressLookup addressLookup) {
        this.properties = properties;
        this.addressLookup = addressLookup;
    }

    public ArticleSourceMaterial resolve(Article article) {
        var storedPublisherContent = ArticleSourceMaterial.fromStoredPublisherContent(article);
        if (storedPublisherContent.isPresent()) return storedPublisherContent.get();
        ArticleSourceMaterial.ExtractedContent extracted = extract(article);
        return ArticleSourceMaterial.choose(article, extracted);
    }

    public Optional<ArticleSourceMaterial.ExtractedContent> extractPublisherContent(Article article) {
        return Optional.ofNullable(extract(article));
    }

    private ArticleSourceMaterial.ExtractedContent extract(Article article) {
        if (article == null || article.getUrl() == null || properties.getAllowedHosts() == null
                || properties.getAllowedHosts().isEmpty()) return null;
        try {
            URI uri = URI.create(article.getUrl()).normalize();
            if (!isSupportedUri(uri) || !isAllowedHost(uri.getHost())) return null;
            InetAddress[] addresses = resolvePublicAddresses(uri.getHost());
            if (addresses.length == 0) return null;

            int timeoutMs = Math.max(500, properties.getTimeoutMs());
            try (CloseableHttpClient client = createPinnedClient(uri.getHost(), addresses, timeoutMs)) {
                if (!robotsAllows(uri, client, timeoutMs)) return null;
                HttpPayload response = get(client, uri, timeoutMs, MAX_RESPONSE_BYTES);
                if (response.status() != 200 || !isHtml(response.firstHeader("Content-Type"))
                        || containsAiRestriction(response.headers("X-Robots-Tag"))) return null;

                Document document = parseHtml(response.body(), response.firstHeader("Content-Type"), uri.toString());
                ArticleSourceMaterial.ExtractedContent content = extractHtml(document, properties.getMaxCharacters());
                if (content == null || hasAiRestriction(document)) return null;
                return content;
            }
        } catch (Exception ex) {
            log.debug("Publisher article extraction skipped for article {}: {}", article.getId(), ex.getClass().getSimpleName());
            return null;
        }
    }

    boolean robotsAllows(URI articleUri, CloseableHttpClient client, int timeoutMs) throws Exception {
        URI robotsUri = new URI(articleUri.getScheme(), null, articleUri.getHost(), articleUri.getPort(), "/robots.txt", null, null);
        HttpPayload response = get(client, robotsUri, timeoutMs, MAX_ROBOTS_BYTES);
        if (response.status() == 404 || response.status() == 410) return true;
        if (response.status() != 200) return false; // Fail closed on redirects, auth and server errors.
        // Unknown AI-specific robots directives are ignored by generic robots parsers.
        // Inspect the whole file and fail closed when a restriction is present.
        if (containsAiRestriction(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8))) return false;
        BaseRobotRules rules = new SimpleRobotRulesParser().parseContent(
                robotsUri.toString(), response.body(), response.firstHeader("Content-Type") == null
                        ? "text/plain" : response.firstHeader("Content-Type"), List.of("dailyaiaassistantbot"));
        return rules.isAllowed(articleUri.toString());
    }

    private HttpPayload get(CloseableHttpClient client, URI uri, int timeoutMs, int maxBytes) throws Exception {
        HttpGet request = new HttpGet(uri);
        request.setHeader("User-Agent", USER_AGENT + " (+https://github.com/shashankkudha16-tech/daily-ai-assistant)");
        request.setHeader("Accept", "text/html,application/xhtml+xml;q=0.9,text/plain;q=0.8");
        request.setConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofMilliseconds(timeoutMs)).build());
        return client.execute(request, response -> {
            byte[] body = new byte[0];
            if (response.getEntity() != null && response.getEntity().getContent() != null) {
                try (InputStream input = response.getEntity().getContent()) {
                    body = readBounded(input, maxBytes);
                }
            }
            List<HeaderValue> headers = Arrays.stream(response.getHeaders())
                    .map(header -> new HeaderValue(header.getName(), header.getValue())).toList();
            return new HttpPayload(response.getCode(), headers, body);
        });
    }

    static CloseableHttpClient createPinnedClient(String host, InetAddress[] validatedAddresses, int timeoutMs) {
        PinnedDnsResolver resolver = new PinnedDnsResolver(host, validatedAddresses);
        Timeout timeout = Timeout.ofMilliseconds(Math.max(500, timeoutMs));
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(timeout).setSocketTimeout(timeout).build())
                .setDefaultSocketConfig(SocketConfig.custom().setSoTimeout(timeout).build())
                .build();
        return HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().build();
    }

    InetAddress[] resolvePublicAddresses(String host) throws UnknownHostException {
        InetAddress[] addresses = addressLookup.resolve(host);
        if (addresses == null || addresses.length == 0 || Arrays.stream(addresses).anyMatch(address -> !isPublicAddress(address))) {
            throw new UnknownHostException("Host resolved to an empty or non-public address set");
        }
        return addresses.clone();
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return BLOCKED_IPV4_CIDRS.stream().noneMatch(cidr -> inCidr(bytes, cidr));
        if (bytes.length != 16 || !startsWithBits(bytes, new byte[]{0x20}, 3)) return false; // global-unicast 2000::/3 only
        return !inCidr(bytes, "2001::/23") && !inCidr(bytes, "2001:db8::/32")
                && !inCidr(bytes, "2002::/16") && !inCidr(bytes, "3fff::/20");
    }

    private static boolean inCidr(byte[] address, String cidr) {
        try {
            String[] parts = cidr.split("/");
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            if (network.length != address.length) return false;
            return startsWithBits(address, network, Integer.parseInt(parts[1]));
        } catch (Exception ignored) {
            return true; // A malformed deny-list entry fails closed.
        }
    }

    private static boolean startsWithBits(byte[] address, byte[] network, int bits) {
        int fullBytes = bits / 8;
        int remainingBits = bits % 8;
        for (int i = 0; i < fullBytes; i++) if (address[i] != network[i]) return false;
        if (remainingBits == 0) return true;
        int mask = 0xff << (8 - remainingBits);
        return (address[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    private boolean isSupportedUri(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) return false;
        int port = uri.getPort();
        return port < 0 || ("http".equalsIgnoreCase(uri.getScheme()) && port == 80)
                || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443);
    }

    private boolean isAllowedHost(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        return properties.getAllowedHosts().stream().filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""))
                .anyMatch(allowed -> normalized.equals(allowed) || normalized.endsWith("." + allowed));
    }

    ArticleSourceMaterial.ExtractedContent extractHtml(Document document, int maximumCharacters) {
        if (hasAiRestriction(document) || looksLikePaywall(document)) return null;
        Element articleBody = document.selectFirst("[itemprop=articleBody]");
        Element selected = articleBody;
        boolean articleRoot = selected != null;
        if (selected == null || selected.text().length() < 300) {
            var articles = document.select("article");
            if (!articles.isEmpty()) {
                selected = articles.stream().max(java.util.Comparator.comparingInt(e -> e.text().length())).orElse(null);
                articleRoot = true;
            }
        }
        if (selected == null || selected.text().length() < 300) {
            // Publisher templates do not consistently use <article>. Prefer
            // semantic body containers and choose the most substantial one.
            var bodyCandidates = document.select(".article-body, .article-content, .post-content, .entry-content, "
                    + ".blog-content, .rich-text, [data-testid*=article-body], [data-testid*=rich-text], .prose");
            selected = bodyCandidates.stream().max(java.util.Comparator.comparingInt(e -> e.text().length())).orElse(null);
            // Class-based containers are useful extraction targets but do not
            // prove that the complete article was rendered in the response.
            articleRoot = false;
        }
        if (selected == null || selected.text().length() < 300) {
            Element main = document.selectFirst("main");
            if (main != null && main.text().length() > (selected == null ? 0 : selected.text().length())) {
                selected = main;
                articleRoot = false;
            }
        }
        if (selected == null) return null;
        removeBoilerplate(selected);
        String text = selected.text().replaceAll("\\s+", " ").trim();
        if (text.length() < 300) return null;
        int max = Math.max(1000, Math.min(100_000, maximumCharacters));
        boolean truncated = text.length() > max;
        if (truncated) text = text.substring(0, max);
        ArticleSourceMaterial.Completeness completeness = truncated
                ? ArticleSourceMaterial.Completeness.TRUNCATED
                : articleRoot ? ArticleSourceMaterial.Completeness.COMPLETE : ArticleSourceMaterial.Completeness.PARTIAL;
        ArticleSourceMaterial.Type type = articleRoot && !truncated
                ? ArticleSourceMaterial.Type.FULL_ARTICLE : ArticleSourceMaterial.Type.EXCERPT;
        return new ArticleSourceMaterial.ExtractedContent(type, completeness, text);
    }

    private boolean hasAiRestriction(Document document) {
        for (Element meta : document.select("meta[name]")) {
            // Crawler names vary by vendor (GPTBot, Googlebot, Bytespider, etc.).
            // A restriction is safer to honor regardless of the metadata name.
            if (containsAiRestriction(meta.attr("content"))) return true;
        }
        return false;
    }

    boolean containsAiRestriction(List<String> values) {
        return values != null && values.stream().anyMatch(this::containsAiRestriction);
    }

    private boolean containsAiRestriction(String value) {
        return value != null && AI_RESTRICTION.matcher(value).find();
    }

    private boolean looksLikePaywall(Document document) {
        if (!document.select("[class*=paywall i], [id*=paywall i], [class*=metered i], [data-testid*=paywall i], [class*=subscriber-only i], [data-locked]").isEmpty()) return true;
        String text = document.body() == null ? "" : document.body().text().toLowerCase(Locale.ROOT);
        return text.contains("subscribe to continue reading") || text.contains("sign in to read the full article")
                || text.contains("this article is for subscribers only") || text.contains("you've reached your limit")
                || text.contains("log in to continue reading");
    }

    private boolean isHtml(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/html");
    }

    private Charset declaredHttpCharset(String contentType) throws Exception {
        if (contentType == null) return null;
        String charset = ContentType.parse(contentType).getParameter("charset");
        return charset == null ? null : Charset.forName(charset.replace("\"", "").trim());
    }

    Document parseHtml(byte[] bytes, String contentType, String baseUri) throws Exception {
        Charset charset = declaredHttpCharset(contentType);
        return Jsoup.parse(new ByteArrayInputStream(bytes), charset == null ? null : charset.name(), baseUri);
    }

    private void removeBoilerplate(Element root) {
        root.select("script,style,nav,footer,header,aside,form,iframe,button,dialog,[role=navigation],[aria-hidden=true]").remove();
    }

    private byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > maxBytes) throw new IOException("Publisher response exceeds the size limit");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private record HeaderValue(String name, String value) {}

    private record HttpPayload(int status, List<HeaderValue> headers, byte[] body) {
        List<String> headers(String name) {
            return headers.stream().filter(header -> header.name().equalsIgnoreCase(name)).map(HeaderValue::value).toList();
        }
        String firstHeader(String name) { return headers(name).stream().findFirst().orElse(null); }
    }
}
