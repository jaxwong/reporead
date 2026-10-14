-- A highlight or card names the client mutation that created it, so listings read it from the row instead of joining
-- the idempotency records. A record that no annotation names is a deleted annotation's: a late replay of it gets 410.
alter table annotations add column mutation_id uuid;
update annotations a set mutation_id = m.mutation_id
from annotation_mutations m where m.annotation_id = a.id and m.user_id = a.user_id;
-- Every highlight and card was created through a mutation; a bookmark never is. Fails, and changes nothing, if any
-- highlight or card has no mutation record.
alter table annotations add constraint annotations_mutation check ((type = 'BOOKMARK') = (mutation_id is null));
create unique index annotations_user_mutation on annotations (user_id, mutation_id);
alter table annotation_mutations drop column annotation_id;
