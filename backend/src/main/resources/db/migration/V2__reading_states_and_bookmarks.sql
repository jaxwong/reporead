-- Stage 2: semantic reading progress and bookmarks. Reading actions never touch source Markdown.

-- One row per user and document. Last write wins by the client's last_read_at; the version actually displayed is recorded.
create table reading_states (
    user_id            bigint      not null references users (id),
    document_id        bigint      not null references documents (id),
    last_read_blob_sha text        not null,
    progress_percent   smallint    not null check (progress_percent between 0 and 100),
    anchor_json        jsonb       not null,
    last_read_at       timestamptz not null,
    primary key (user_id, document_id)
);
create index reading_states_recent on reading_states (user_id, last_read_at desc);

-- The annotation domain; Stage 2 stores only document-level BOOKMARKs. Stage 3 extends it with highlights and anchors.
create table annotations (
    id              bigint generated always as identity primary key,
    user_id         bigint      not null references users (id),
    document_id     bigint      not null references documents (id),
    source_blob_sha text        not null,
    type            text        not null check (type in ('BOOKMARK')),
    created_at      timestamptz not null
);
create unique index annotations_one_bookmark_per_document on annotations (user_id, document_id) where type = 'BOOKMARK';
