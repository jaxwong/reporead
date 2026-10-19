-- Keep the original immutable request timestamp for replay checks; reviewed_at owns scheduling time.
-- Text preserves accepted ISO instants outside PostgreSQL's timestamp range after reviewed_at is clamped.
-- NULL is a pre-V15 row: its reviewed_at is also its original client timestamp.
alter table review_log add column client_reviewed_at text;
