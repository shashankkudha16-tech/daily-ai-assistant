package com.dailyai.assistant.integration;

import com.dailyai.assistant.entity.Article;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RssParserTest {

    private final RssParser parser = new RssParser();

    private static final String RSS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Example AI Blog</title>
                <link>https://example.com</link>
                <description>Example feed</description>
                <item>
                  <title>New open model released</title>
                  <link>https://example.com/posts/open-model?utm_source=rss</link>
                  <description><![CDATA[<p>A new <b>open weight</b> model was released.</p>]]></description>
                  <pubDate>Mon, 16 Nov 2026 09:30:00 GMT</pubDate>
                  <author>Jane Doe</author>
                </item>
                <item>
                  <title>No date item</title>
                  <link>https://example.com/posts/no-date</link>
                  <description>Text only.</description>
                </item>
                <item>
                  <title>Duplicate link</title>
                  <link>https://example.com/posts/no-date</link>
                </item>
              </channel>
            </rss>
            """;

    @Test
    void parsesEntriesWithoutInventingDates() throws Exception {
        List<ParsedArticle> articles = parser.parse(RSS.getBytes(StandardCharsets.UTF_8), "fallback");

        assertThat(articles).hasSize(2);
        ParsedArticle first = articles.get(0);
        assertThat(first.title()).isEqualTo("New open model released");
        assertThat(first.url()).isEqualTo("https://example.com/posts/open-model?utm_source=rss");
        assertThat(first.description()).isEqualTo("A new open weight model was released.");
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-11-16T09:30:00Z"));
        assertThat(first.source()).isEqualTo("Example AI Blog");
        assertThat(first.author()).isEqualTo("Jane Doe");
        assertThat(first.rssContentAiAllowed()).isFalse();

        ParsedArticle allowed = parser.parse(RSS.getBytes(StandardCharsets.UTF_8), "fallback", true).get(0);
        assertThat(allowed.rssContentAiAllowed()).isTrue();

        ParsedArticle second = articles.get(1);
        assertThat(second.title()).isEqualTo("No date item");
        assertThat(second.publishedAt()).isNull();
        assertThat(second.source()).isEqualTo("Example AI Blog");
        assertThat(second.author()).isNull();
    }

    @Test
    void parsesAtomAuthorAndDublinCoreCreator() throws Exception {
        String atom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <title>Atom Publisher</title>
                  <entry><title>Atom article</title><id>https://example.com/atom</id>
                    <link href="https://example.com/atom"/><author><name>Alex Atom</name></author></entry>
                  <entry><title>Metadata article</title><id>https://example.com/dc</id>
                    <link href="https://example.com/dc"/><dc:creator>Casey Creator</dc:creator></entry>
                </feed>
                """;

        List<ParsedArticle> articles = parser.parse(atom.getBytes(StandardCharsets.UTF_8), "fallback");

        assertThat(articles).extracting(ParsedArticle::author).containsExactly("Alex Atom", "Casey Creator");
        assertThat(articles).extracting(ParsedArticle::source).containsOnly("Atom Publisher");
    }

    @Test
    void preservesSanitizedAndBoundedRssFullContentSeparatelyFromTheExcerpt() throws Exception {
        String rss = """
                <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
                  <channel><title>Publisher</title><item><title>Story</title>
                    <link>https://example.com/story</link><description><![CDATA[<p>Short excerpt</p>]]></description>
                    <content:encoded><![CDATA[<article><p>Full publisher content.</p><script>bad()</script></article>]]></content:encoded>
                  </item></channel>
                </rss>
                """;

        ParsedArticle parsed = parser.parse(rss.getBytes(StandardCharsets.UTF_8), "fallback").get(0);

        assertThat(parsed.description()).isEqualTo("Short excerpt");
        assertThat(parsed.rssContent()).contains("Full publisher content.").doesNotContain("bad()");
        assertThat(parsed.rssContentAiAllowed()).isFalse();
    }

    @Test
    void doesNotPromoteUnpermittedFullContentToExcerptWhenDescriptionIsMissing() throws Exception {
        String rss = """
                <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
                  <channel><title>Publisher</title><item><title>Story</title>
                    <link>https://example.com/story</link>
                    <content:encoded><![CDATA[<article><p>Full body only.</p></article>]]></content:encoded>
                  </item></channel>
                </rss>
                """;

        ParsedArticle denied = parser.parse(rss.getBytes(StandardCharsets.UTF_8), "fallback", false).get(0);
        assertThat(denied.rssContent()).contains("Full body only.");
        assertThat(denied.description()).isNull();
        assertThat(denied.rssContentAiAllowed()).isFalse();

        Article article = new Article();
        article.setTitle(denied.title());
        article.setDescription(denied.description());
        article.setRssContent(denied.rssContent());
        article.setRssContentAiAllowed(denied.rssContentAiAllowed());
        ArticleSourceMaterial selected = ArticleSourceMaterial.fromFeed(article);
        assertThat(selected.type()).isEqualTo(ArticleSourceMaterial.Type.TITLE_ONLY);
        assertThat(selected.text()).isEqualTo("Story");

        ParsedArticle allowed = parser.parse(rss.getBytes(StandardCharsets.UTF_8), "fallback", true).get(0);
        assertThat(allowed.description()).contains("Full body only.");
        assertThat(allowed.rssContentAiAllowed()).isTrue();
        article.setDescription(allowed.description());
        article.setRssContentAiAllowed(true);
        assertThat(ArticleSourceMaterial.fromFeed(article).type()).isEqualTo(ArticleSourceMaterial.Type.RSS_CONTENT);
    }

    @Test
    void rejectsMalformedXml() {
        byte[] broken = "<rss><channel><item>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> parser.parse(broken, "broken")).isInstanceOf(Exception.class);
    }
}
