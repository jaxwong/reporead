-- A highlight's or card's version is its anchor's (annotation_anchors.source_blob_sha), the copy every read uses; this
-- column repeated it. For a bookmark it recorded the version bookmarked, which nothing read: a bookmark is of the note.
alter table annotations drop column source_blob_sha;
