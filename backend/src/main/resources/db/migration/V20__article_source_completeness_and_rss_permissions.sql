alter table articles
    add column rss_content_truncated boolean not null default false,
    add column rss_content_ai_allowed boolean not null default false;

alter table article_explanations
    add column source_completeness varchar(20) not null default 'UNKNOWN';
