create table personal_learning_plan_items (
    id bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    plan_date date not null,
    section varchar(20) not null check (section in ('LEARN', 'PRACTICE', 'STAY_UPDATED')),
    title varchar(250) not null,
    description text not null,
    rationale text not null,
    priority varchar(10) not null check (priority in ('HIGH', 'MEDIUM', 'LOW')),
    estimated_minutes integer not null check (estimated_minutes between 5 and 240),
    related_skill varchar(200),
    due_date date not null,
    status varchar(12) not null default 'PENDING' check (status in ('PENDING', 'COMPLETED')),
    source_article_id bigint references articles(id) on delete set null,
    source_topic_id bigint references learning_topics(id) on delete set null,
    source_progress_entry_id bigint references user_progress_entries(id) on delete set null,
    completed_at timestamptz,
    created_at timestamptz not null default now()
);

create index idx_personal_plan_user_date on personal_learning_plan_items(user_id, plan_date desc, section);
create unique index uk_personal_plan_item_identity on personal_learning_plan_items(user_id, plan_date, section, title);
