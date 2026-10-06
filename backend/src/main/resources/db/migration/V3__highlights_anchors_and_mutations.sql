-- Stage 3: highlights with optional notes, their anchors, and idempotent offline creation.

alter table annotations drop constraint annotations_type_check;
alter table annotations add constraint annotations_type_check check (type in ('BOOKMARK', 'HIGHLIGHT'));
alter table annotations add column note text check (note is null or length(note) <= 10000);
-- Stage 4 adds REANCHORED and ORPHANED.
alter table annotations add column status text not null default 'ANCHORED' check (status in ('ANCHORED'));
alter table annotations add column version integer not null default 1 check (version >= 1);
alter table annotations add column updated_at timestamptz;
update annotations set updated_at = created_at;
alter table annotations alter column updated_at set not null;
create index annotations_user_document on annotations (user_id, document_id);

-- Where a highlight was made: canonical block text of one source version, UTF-16 offsets (see the Stage 0 contract).
-- prefix/suffix/heading_path are derived by the server from that version, never taken from the client.
create table annotation_anchors (
    annotation_id   bigint  primary key references annotations (id) on delete cascade,
    source_blob_sha text    not null,
    block_id        text    not null,
    exact_text      text    not null,
    prefix_text     text    not null,
    suffix_text     text    not null,
    start_offset    integer not null check (start_offset >= 0),
    end_offset      integer not null check (end_offset > start_offset),
    heading_path    jsonb   not null
);

-- One row per client mutation id: replaying the same request returns the original result; different content is rejected.
create table annotation_mutations (
    user_id       bigint      not null references users (id),
    mutation_id   uuid        not null,
    request_hash  bytea       not null,
    annotation_id bigint      references annotations (id) on delete set null,
    created_at    timestamptz not null,
    primary key (user_id, mutation_id)
);
