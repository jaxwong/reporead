-- P4: cards reuse annotation anchors, locations, and creation mutations. Scheduling stays on the phone.
alter table annotations drop constraint annotations_type_check;
alter table annotations add constraint annotations_type_check check (type in ('BOOKMARK', 'HIGHLIGHT', 'CARD'));
alter table annotations add column question text;
alter table annotations add column checked_blob_sha text;
alter table annotations add constraint annotations_card_question check
    ((type = 'CARD' and question is not null and length(trim(question)) between 1 and 10000) or (type <> 'CARD' and question is null));
alter table annotations add constraint annotations_card_resolved check (type <> 'CARD' or resolved_blob_sha is not null);

create table review_log (
    user_id bigint not null references users(id) on delete cascade,
    mutation_id uuid not null,
    annotation_id bigint not null references annotations(id) on delete cascade,
    grade integer not null check (grade between 0 and 5),
    reviewed_at timestamptz not null,
    blob_sha text not null,
    primary key (user_id, mutation_id)
);
create index review_log_card on review_log(annotation_id);
