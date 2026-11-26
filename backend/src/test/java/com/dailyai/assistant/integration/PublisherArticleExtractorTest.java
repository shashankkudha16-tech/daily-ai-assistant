package com.dailyai.assistant.integration;

import com.dailyai.assistant.config.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublisherArticleExtractorTest {
    private final PublisherArticleExtractor extractor = new PublisherArticleExtractor(new AppProperties());

    @Test
    void extractsCompleteArticleAndRemovesBoilerplate() {
        String body = "The publisher reports a useful source fact. ".repeat(12);
        String html = "<html><body><nav>Navigation must not be included</nav><article><h1>Title</h1>"
                + "<p>" + body + "</p><script>tracking must not be included</script></article></body></html>";

        var extracted = extractor.extractHtml(org.jsoup.Jsoup.parse(html), 30000);

        assertThat(extracted.type()).isEqualTo(ArticleSourceMaterial.Type.FULL_ARTICLE);
        assertThat(extracted.completeness()).isEqualTo(ArticleSourceMaterial.Completeness.COMPLETE);
        assertThat(extracted.text()).contains("The publisher reports a useful source fact.")
                .doesNotContain("Navigation", "tracking");
    }

    @Test
    void marksContentAboveTheConfiguredLimitTruncatedAndNeverFullArticle() {
        String body = "The publisher reports a useful source fact. ".repeat(100);
        String html = "<article><p>" + body + "</p></article>";

        var extracted = extractor.extractHtml(org.jsoup.Jsoup.parse(html), 1000);

        assertThat(extracted.type()).isEqualTo(ArticleSourceMaterial.Type.EXCERPT);
        assertThat(extracted.completeness()).isEqualTo(ArticleSourceMaterial.Completeness.TRUNCATED);
        assertThat(extracted.text()).hasSize(1000);
    }

    @Test
    void rejectsTooShortArticleTextAndLabelsMainOnlyExtractionPartial() {
        assertThat(extractor.extractHtml(org.jsoup.Jsoup.parse("<article>Too short.</article>"), 30000)).isNull();

        var partial = extractor.extractHtml(org.jsoup.Jsoup.parse("<main>" + "Partial page text. ".repeat(30) + "</main>"), 30000);
        assertThat(partial.type()).isEqualTo(ArticleSourceMaterial.Type.EXCERPT);
        assertThat(partial.completeness()).isEqualTo(ArticleSourceMaterial.Completeness.PARTIAL);
    }

    @Test
    void fallsThroughShortPublisherArticleRootsToDeepmindRichTextFixture() throws Exception {
        var html = new String(getClass().getResourceAsStream("/publisher-fixtures/deepmind-alphaproof-code.html").readAllBytes(), StandardCharsets.UTF_8);
        var extracted = extractor.extractHtml(org.jsoup.Jsoup.parse(html), 30000);

        assertThat(extracted).isNotNull();
        assertThat(extracted.text()).contains("Fixture-only body text demonstrates the Google DeepMind rich-text layout")
                .doesNotContain("surrounding page contains navigation");
        assertThat(extracted.type()).isEqualTo(ArticleSourceMaterial.Type.EXCERPT);
        assertThat(extracted.completeness()).isEqualTo(ArticleSourceMaterial.Completeness.PARTIAL);
    }

    @Test
    void fallsThroughShortPublisherArticleRootsToHuggingFaceBlogFixture() throws Exception {
        var html = new String(getClass().getResourceAsStream("/publisher-fixtures/huggingface-agentic-evals.html").readAllBytes(), StandardCharsets.UTF_8);
        var extracted = extractor.extractHtml(org.jsoup.Jsoup.parse(html), 30000);

        assertThat(extracted).isNotNull();
        assertThat(extracted.text()).contains("Fixture-only text exercises the Hugging Face blog-content layout");
        assertThat(extracted.type()).isEqualTo(ArticleSourceMaterial.Type.EXCERPT);
        assertThat(extracted.completeness()).isEqualTo(ArticleSourceMaterial.Completeness.PARTIAL);
    }

    @Test
    void refusesAiRestrictedAndPaywalledPagesIncludingCrawlerSpecificMeta() {
        String body = "The publisher reports a useful source fact. ".repeat(12);
        assertThat(extractor.extractHtml(org.jsoup.Jsoup.parse("<meta name='robots' content='noai'><article>" + body + "</article>"), 30000)).isNull();
        assertThat(extractor.extractHtml(org.jsoup.Jsoup.parse("<meta name='googlebot' content='no-llm'><article>" + body + "</article>"), 30000)).isNull();
        assertThat(extractor.extractHtml(org.jsoup.Jsoup.parse("<meta name='bingbot' content='noai'><article>" + body + "</article>"), 30000)).isNull();
        assertThat(extractor.extractHtml(org.jsoup.Jsoup.parse("<article class='paywall'>" + body + "</article>"), 30000)).isNull();
    }

    @Test
    void checksEveryXRobotsHeaderValue() {
        assertThat(extractor.containsAiRestriction(List.of("index, follow", "noai"))).isTrue();
        assertThat(extractor.containsAiRestriction(List.of("noarchive", "no-llm"))).isTrue();
        assertThat(extractor.containsAiRestriction(List.of("index, follow", "max-image-preview:large"))).isFalse();
    }

    @Test
    void failsClosedOnAiRestrictionsInRobotsTxt() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/robots.txt", exchange -> {
            byte[] body = "User-agent: *\nNoAI: true\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URI articleUri = URI.create("http://publisher.test:" + server.getAddress().getPort() + "/article");
            InetAddress pinnedAddress = InetAddress.getByName("127.0.0.1");
            try (CloseableHttpClient client = PublisherArticleExtractor.createPinnedClient(
                    "publisher.test", new InetAddress[]{pinnedAddress}, 3000)) {
                assertThat(extractor.robotsAllows(articleUri, client, 3000)).isFalse();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void decodesUtf8AndHttpDeclaredLegacyCharsetsAndDetectsHtmlCharset() throws Exception {
        String content = "La compañía publicó información sobre la investigación. ".repeat(8);
        byte[] latin1 = ("<article><p>" + content + "</p></article>").getBytes(Charset.forName("ISO-8859-1"));
        var legacyDoc = extractor.parseHtml(latin1, "text/html; charset=ISO-8859-1", "https://example.test/a");
        assertThat(legacyDoc.text()).contains("compañía");

        byte[] utf8 = ("<meta charset='UTF-8'><article><p>" + content + "</p></article>").getBytes(StandardCharsets.UTF_8);
        var utf8Doc = extractor.parseHtml(utf8, "text/html", "https://example.test/a");
        assertThat(utf8Doc.text()).contains("compañía");
    }

    @Test
    void rejectsMixedPublicAndPrivateDnsAnswers() throws Exception {
        InetAddress publicIp = InetAddress.getByName("8.8.8.8");
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        InetAddress carrierNat = InetAddress.getByName("100.64.0.1");
        InetAddress reserved = InetAddress.getByName("192.0.2.4");
        InetAddress ipv6Documentation = InetAddress.getByName("3fff::1");
        assertThat(PublisherArticleExtractor.isPublicAddress(publicIp)).isTrue();
        assertThat(PublisherArticleExtractor.isPublicAddress(loopback)).isFalse();
        assertThat(PublisherArticleExtractor.isPublicAddress(carrierNat)).isFalse();
        assertThat(PublisherArticleExtractor.isPublicAddress(reserved)).isFalse();
        assertThat(PublisherArticleExtractor.isPublicAddress(ipv6Documentation)).isFalse();
        PublisherArticleExtractor mixedResolver = new PublisherArticleExtractor(new AppProperties().getArticleContent(),
                host -> new InetAddress[]{publicIp, loopback});
        assertThatThrownBy(() -> mixedResolver.resolvePublicAddresses("mixed.test"))
                .isInstanceOf(java.net.UnknownHostException.class);
    }

    @Test
    void pinnedDnsDoesNotReResolveAfterTheValidatedAnswerChanges() throws Exception {
        InetAddress initiallyResolved = InetAddress.getByName("8.8.8.8");
        InetAddress laterResolution = InetAddress.getByName("10.0.0.8");
        AtomicReference<InetAddress> dns = new AtomicReference<>(initiallyResolved);
        InetAddress[] validated = {dns.get()};
        PinnedDnsResolver pinned = new PinnedDnsResolver("publisher.test", validated);
        dns.set(laterResolution);

        assertThat(pinned.resolve("publisher.test")).containsExactly(initiallyResolved);
        assertThat(dns.get()).isEqualTo(laterResolution);
        assertThatThrownBy(() -> pinned.resolve("redirected.test")).isInstanceOf(java.net.UnknownHostException.class);
    }

    @Test
    void actualHttpConnectionUsesPinnedAddressInsteadOfChangedDns() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] response = "reached pinned endpoint".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            InetAddress validatedEndpoint = InetAddress.getByName("127.0.0.1");
            InetAddress dnsAfterValidation = InetAddress.getByName("10.1.2.3");
            InetAddress[] mutableDnsResult = {validatedEndpoint};
            PinnedDnsResolver pinned = new PinnedDnsResolver("publisher.test", mutableDnsResult);
            mutableDnsResult[0] = dnsAfterValidation;
            try (CloseableHttpClient client = PublisherArticleExtractor.createPinnedClient(
                    "publisher.test", pinned.resolve("publisher.test"), 3000)) {
                HttpGet request = new HttpGet(URI.create("http://publisher.test:" + server.getAddress().getPort() + "/"));
                String response = client.execute(request, result -> new String(
                        result.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8));
                assertThat(response).isEqualTo("reached pinned endpoint");
                assertThat(pinned.resolve("publisher.test")).containsExactly(validatedEndpoint);
            }
        } finally {
            server.stop(0);
        }
    }
}
