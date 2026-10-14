-- last_synced_at was written by every refresh and never read. current_commit_sha copied the connection's
-- last_synced_commit_sha, which every active document is at, so it is read from the connection instead. Without them
-- a refresh rewrites only documents whose content changed or that came back.
alter table documents drop column last_synced_at;
alter table documents drop column current_commit_sha;
