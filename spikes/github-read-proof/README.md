# Read-only note snapshot proof

This development tool proves GitHub App installation read access and prepares input for the independent renderer test. It does **not** prove user sign-in, repository eligibility for a signed-in user, or a live backend-to-phone integration.

```sh
python3 spikes/github-read-proof/read_note.py --help
```

Pass the App ID, installation ID, private PEM path, repository, note path, and two output paths outside this repository. Maximum: three GitHub calls (app metadata, one restricted installation token, one content request), 15 seconds each, no redirects/retries/alternate strategies, 1 MiB note and 4 MiB API response limits. The note's Git blob SHA is checked before writing. Output files are mode 0600; tokens and note content are never printed.

This is intentionally a selected snapshot, not an automatic fallback for a failed live integration. Do not distribute private note inputs, exports, or a debug APK containing them.
