alter table users
    add column career_goal text not null default '',
    add column target_roles text not null default '';
