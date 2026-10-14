-- Every location records how distinguishable its passage is in its version, which Anchoring requires. V4 copied the
-- locations of earlier highlights without it, and listing filled each one in; none is left without it. block_sha stays
-- nullable: it is null whenever the block's text is not unique in that version.
alter table annotation_locations alter column quote_occurrences set not null;
alter table annotation_locations alter column rival_context set not null;
alter table annotation_locations alter column rival_quote set not null;
