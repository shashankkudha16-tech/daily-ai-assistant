-- V17 mapped legacy false values to SHOW_ME_FEWER. In V16, false meant Not useful.
-- Only correct rows that still carry the legacy false value; new SHOW_ME_FEWER
-- actions leave useful NULL so they remain distinct and are not changed here.
update guest_article_interactions
set feedback_action = 'NOT_USEFUL'
where useful is false
  and feedback_action = 'SHOW_ME_FEWER';
