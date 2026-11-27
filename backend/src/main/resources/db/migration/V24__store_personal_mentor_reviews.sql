create table mentor_reviews (
    id bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    review_date date not null,
    status varchar(16) not null,
    prompt_version varchar(32) not null,
    context_hash varchar(64) not null,
    report_json text not null,
    generated_at timestamptz not null default now(),
    constraint uk_mentor_review_user_date unique (user_id, review_date),
    constraint ck_mentor_review_status check (status in ('AI', 'BASIC'))
);

create index idx_mentor_review_user_date on mentor_reviews(user_id, review_date desc);
