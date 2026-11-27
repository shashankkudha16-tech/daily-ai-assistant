alter table articles
    add column region varchar(12) not null default 'UNKNOWN';

alter table articles
    add constraint chk_articles_region check (region in ('INDIA', 'GLOBAL', 'UNKNOWN'));

create index idx_articles_region_published_at on articles(region, published_at desc);

insert into categories(name, slug, description)
values ('India Updates', 'india-updates', 'Updates from India-focused sources.')
on conflict (slug) do nothing;
