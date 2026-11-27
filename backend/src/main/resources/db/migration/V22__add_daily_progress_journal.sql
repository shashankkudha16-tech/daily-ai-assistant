create table user_progress_entries (
    id bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    entry_date date not null,
    learned text not null default '',
    practiced text not null default '',
    built text not null default '',
    difficulties text not null default '',
    tomorrow_goals text not null default '',
    attachment_name varchar(255),
    attachment_type varchar(120),
    attachment_text text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uk_progress_entry_user_date unique (user_id, entry_date),
    constraint ck_progress_attachment_metadata check (
        (attachment_name is null and attachment_type is null and attachment_text is null)
        or (attachment_name is not null and attachment_type is not null and attachment_text is not null)
    )
);

create index idx_progress_entry_user_date on user_progress_entries(user_id, entry_date desc);
