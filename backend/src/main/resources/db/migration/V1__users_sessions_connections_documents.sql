-- Stage 1: identity, app sessions, authorized repository connections, and logical Markdown documents.
-- Foreign keys deliberately do not cascade: disconnect/account-deletion retention is decided in Stage 6.

create table users (
    id             bigint generated always as identity primary key,
    github_user_id bigint      not null unique check (github_user_id > 0),
    login          text        not null check (login <> ''),
    created_at     timestamptz not null default now()
);

-- Single-use PKCE handoff from the browser OAuth callback to the Android app. Only hashes are stored.
create table app_sign_in_codes (
    code_hash      bytea       primary key,
    user_id        bigint      not null references users (id),
    code_challenge text        not null,
    expires_at     timestamptz not null
);

-- Opaque bearer sessions for the Android app. Only hashes are stored.
create table app_sessions (
    token_hash bytea       primary key,
    user_id    bigint      not null references users (id),
    created_at timestamptz not null,
    expires_at timestamptz not null
);

create table repository_connections (
    id                     bigint generated always as identity primary key,
    user_id                bigint      not null references users (id),
    github_repository_id   bigint      not null check (github_repository_id > 0),
    installation_id        bigint      not null check (installation_id > 0),
    owner                  text        not null,
    name                   text        not null,
    default_branch         text        not null,
    last_synced_commit_sha text,
    last_synced_at         timestamptz,
    created_at             timestamptz not null default now(),
    unique (user_id, github_repository_id)
);

create table documents (
    id                       bigint generated always as identity primary key,
    repository_connection_id bigint      not null references repository_connections (id),
    path                     text        not null,
    title                    text        not null,
    current_blob_sha         text        not null,
    current_commit_sha       text        not null,
    last_synced_at           timestamptz not null,
    deleted_at               timestamptz,
    unique (repository_connection_id, path)
);
