-- A bookmark is document-level state, like a reading state: it has no anchor, location, version or mutation, so it
-- leaves the annotations table, which keeps highlights and cards (ADR-17).
-- The cascades keep the previous release's disconnect and account deletion working: it does not know this table.
create table bookmarks (
    user_id     bigint      not null references users (id) on delete cascade,
    document_id bigint      not null references documents (id) on delete cascade,
    created_at  timestamptz not null,
    primary key (user_id, document_id)
);
create index bookmarks_document on bookmarks (document_id);

-- Moved, not copied, so no bookmark has two rows. The previous release still runs on this schema, but lists no
-- bookmarks; the type check and the unique bookmark index go in a later release.
insert into bookmarks (user_id, document_id, created_at)
select user_id, document_id, created_at from annotations where type = 'BOOKMARK';
delete from annotations where type = 'BOOKMARK';
