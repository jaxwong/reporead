-- A card is an interview-bank question, never confirmed in a version of its note (ADR-13), so the version it was last
-- confirmed in has no reader.
-- Contract: the release of PR #96 (commit 4851d4b) stopped reading and writing checked_blob_sha.
alter table annotations drop column checked_blob_sha;
