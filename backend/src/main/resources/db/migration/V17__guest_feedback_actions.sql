alter table guest_article_interactions add column if not exists feedback_action varchar(24);

update guest_article_interactions
set feedback_action = case
    when useful is true then 'USEFUL'
    when useful is false then 'SHOW_ME_FEWER'
    else null
end
where feedback_action is null and useful is not null;
