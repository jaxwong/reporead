-- Stage 4: highlights follow their passage across note versions; uncertain ones are orphaned and can be reattached.
-- annotation_anchors keeps each original selection unchanged.

alter table annotations drop constraint annotations_status_check;
alter table annotations add constraint annotations_status_check check (status in ('ANCHORED', 'REANCHORED', 'ORPHANED'));

-- The version a highlight's status refers to: where it is shown (ANCHORED, REANCHORED) or was not found (ORPHANED).
alter table annotations add column resolved_blob_sha text;
update annotations a set resolved_blob_sha = n.source_blob_sha from annotation_anchors n where n.annotation_id = a.id;
alter table annotations add constraint annotations_highlight_resolved check (type <> 'HIGHLIGHT' or resolved_blob_sha is not null);

-- A highlight's latest trusted location: where it was made, re-anchored, or reattached. While ORPHANED it stays the
-- last place the passage was known to be, and the next version is resolved from it.
create table annotation_locations (
    annotation_id     bigint           primary key references annotations (id) on delete cascade,
    source_blob_sha   text             not null,
    block_id          text             not null,
    exact_text        text             not null,
    prefix_text       text             not null,
    suffix_text       text             not null,
    start_offset      integer          not null check (start_offset >= 0),
    end_offset        integer          not null check (end_offset > start_offset),
    heading_path      jsonb            not null,
    -- How distinguishable the passage is in source_blob_sha (see Anchoring). Null only for locations copied from
    -- highlights made before Stage 4; the server fills them from that version before resolving it elsewhere.
    block_sha         text,
    quote_occurrences integer          check (quote_occurrences >= 1),
    rival_context     double precision check (rival_context between 0 and 1),
    rival_quote       double precision check (rival_quote between 0 and 1)
);
insert into annotation_locations (annotation_id, source_blob_sha, block_id, exact_text, prefix_text, suffix_text,
                                  start_offset, end_offset, heading_path)
select annotation_id, source_blob_sha, block_id, exact_text, prefix_text, suffix_text, start_offset, end_offset, heading_path
from annotation_anchors;
