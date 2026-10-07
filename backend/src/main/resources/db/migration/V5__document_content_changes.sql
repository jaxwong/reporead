-- Stage 5: when a repository refresh found each note new, changed, or back after being deleted ("Recently changed").
-- Null when RepoRead has never seen the note change: notes from a connection's first refresh, and every note before this.
alter table documents add column content_changed_at timestamptz;
