-- GitHub user tokens, kept so sign-ins survive restarts and deploys (ADR-03). Both tokens are AES-256-GCM ciphertexts
-- (12-byte IV, then ciphertext and tag) under a key file outside the database; GitHubTokens owns the format.
-- No foreign key to users: Spring saves the token during the OAuth callback, before AppSignInSuccess saves the user.
create table github_tokens (
    github_user_id          bigint      primary key check (github_user_id > 0),
    access_token            bytea       not null,
    access_token_issued_at  timestamptz,
    access_token_expires_at timestamptz,
    refresh_token           bytea,
    refresh_token_issued_at timestamptz,
    updated_at              timestamptz not null
);
