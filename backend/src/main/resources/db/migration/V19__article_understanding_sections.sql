-- Additive article-content and explanation fields; existing cached explanations remain intact.
-- Full publisher-page fetching is disabled until reviewed hostnames are configured with
-- APP_ARTICLE_CONTENT_ALLOWED_HOSTS (comma-separated). RSS content remains optional.
alter table articles add column rss_content text;
alter table article_explanations add column why_it_happened text;
alter table article_explanations add column useful_to_reader text;
alter table article_explanations add column recommended_action text;
alter table article_explanations add column source_type varchar(20);
