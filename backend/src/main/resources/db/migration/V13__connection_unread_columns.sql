-- Neither was ever read: last_synced_at was set by every refresh (as documents.last_synced_at was until V10), and
-- installation_id was saved at connect only to be sent to the app, which ignored it. Connecting still names the
-- installation, to find the repository in GitHub's listing.
alter table repository_connections drop column last_synced_at;
alter table repository_connections drop column installation_id;
