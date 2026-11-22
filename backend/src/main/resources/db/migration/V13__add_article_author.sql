-- Keep existing article rows untouched; authors are populated on future feed refreshes.
alter table articles add column author varchar(300);
