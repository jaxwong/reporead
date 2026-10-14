-- Reads and deletes that filter by document or by user alone scanned the whole table: disconnecting, counting what it
-- would delete, finding notes that carry user data, signing out, deleting an account, and the foreign-key checks
-- PostgreSQL makes when a document or a user is deleted.
create index annotations_document on annotations (document_id);
create index reading_states_document on reading_states (document_id);
create index app_sessions_user on app_sessions (user_id);
create index app_sign_in_codes_user on app_sign_in_codes (user_id);
-- Replaced: per-user reads use annotations_user_mutation (V9) or the bookmark index, per-document reads the one above.
drop index annotations_user_document;
