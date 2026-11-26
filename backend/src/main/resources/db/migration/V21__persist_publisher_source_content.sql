alter table articles
    add column publisher_source_content text,
    add column publisher_source_type varchar(20),
    add column publisher_source_completeness varchar(20),
    add constraint ck_articles_publisher_content_presence check (
        (publisher_source_content is null
            and publisher_source_type is null
            and publisher_source_completeness is null)
        or (publisher_source_content is not null
            and length(btrim(publisher_source_content)) > 0
            and publisher_source_type is not null
            and publisher_source_completeness is not null)
    ),
    add constraint ck_articles_publisher_content_type check (
        publisher_source_type is null or publisher_source_type in ('FULL_ARTICLE', 'EXCERPT')
    ),
    add constraint ck_articles_publisher_content_completeness check (
        publisher_source_completeness is null
            or publisher_source_completeness in ('COMPLETE', 'PARTIAL', 'TRUNCATED')
    ),
    add constraint ck_articles_publisher_content_status check (
        publisher_source_type is null
        or (publisher_source_type = 'FULL_ARTICLE' and publisher_source_completeness = 'COMPLETE')
        or (publisher_source_type = 'EXCERPT' and publisher_source_completeness in ('PARTIAL', 'TRUNCATED'))
    );
