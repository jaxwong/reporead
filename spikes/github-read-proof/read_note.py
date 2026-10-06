"""Development-only GitHub App read proof. No user-sign-in or backend integration claim."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

MAX_NOTE_BYTES = 1_048_576
MAX_RESPONSE_BYTES = 4_194_304


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def api(path, token, method="GET", body=None):
    request = urllib.request.Request("https://api.github.com" + path,
        data=json.dumps(body).encode() if body is not None else None,
        method=method, headers={"Accept": "application/vnd.github+json",
            "Authorization": "Bearer " + token, "X-GitHub-Api-Version": "2026-03-10",
            "User-Agent": "RepoRead-Stage0-ReadProof"})
    try:
        with urllib.request.build_opener(NoRedirects()).open(request, timeout=15) as response:
            data = response.read(MAX_RESPONSE_BYTES + 1)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"GitHub {method} {path}: HTTP {error.code}; no retry or alternate fetch") from None
    if len(data) > MAX_RESPONSE_BYTES:
        raise RuntimeError(f"GitHub {method} {path}: response exceeds 4 MiB proof limit")
    return json.loads(data)


def private_write(path, content):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT, 0o600)
    with os.fdopen(fd, "wb") as stream:
        os.fchmod(stream.fileno(), 0o600)
        stream.truncate(0)
        stream.write(content)


def main():
    parser = argparse.ArgumentParser(description="Read one note with at most 3 GitHub calls; no retries or redirects")
    parser.add_argument("--app-id", required=True, type=int)
    parser.add_argument("--installation-id", required=True, type=int)
    parser.add_argument("--key-file", required=True, type=Path)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--path", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--metadata-output", required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository):
        parser.error("repository must be owner/name")
    if any(segment in ("", ".", "..") for segment in args.path.split("/")):
        parser.error("path must be a repository-relative file path without traversal")
    if args.key_file.stat().st_mode & 0o077:
        parser.error("key file must not be accessible to group or other users")
    if args.output.resolve() == args.metadata_output.resolve():
        parser.error("note and metadata output paths must differ")
    root = Path(__file__).resolve().parents[2]
    for output in (args.output, args.metadata_output):
        if output.resolve().is_relative_to(root):
            parser.error("private proof output must be outside the repository")

    def encoded(value):
        return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=").decode()

    now = int(time.time())
    message = encoded({"alg": "RS256", "typ": "JWT"}) + "." + encoded({"iat": now - 60, "exp": now + 300, "iss": args.app_id})
    signature = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(args.key_file)],
        input=message.encode(), capture_output=True, check=True, timeout=5).stdout
    jwt = message + "." + base64.urlsafe_b64encode(signature).rstrip(b"=").decode()
    app = api("/app", jwt)
    if app["id"] != args.app_id:
        raise RuntimeError("GitHub /app returned an unexpected app identity")
    installation = api(f"/app/installations/{args.installation_id}/access_tokens", jwt, "POST",
        {"repositories": [args.repository.split("/")[1]], "permissions": {"contents": "read"}})
    repositories = [repo["full_name"].lower() for repo in installation["repositories"]]
    if repositories != [args.repository.lower()] or installation["permissions"]["contents"] != "read":
        raise RuntimeError("installation token scope differs from the one requested read-only repository")
    note = api(f"/repos/{args.repository}/contents/{urllib.parse.quote(args.path, safe='/')}", installation["token"])
    if note.get("type") != "file" or note.get("encoding") != "base64" or note["size"] > MAX_NOTE_BYTES:
        raise RuntimeError("GitHub content must be a base64 file within the 1 MiB proof limit")
    raw = base64.b64decode("".join(note["content"].split()), validate=True)
    raw.decode("utf-8")
    sha = hashlib.sha1(f"blob {len(raw)}\0".encode() + raw).hexdigest()
    if len(raw) != note["size"] or sha != note["sha"]:
        raise RuntimeError("GitHub note size or blob SHA does not match its bytes")
    metadata = {"clientId": app["client_id"], "appId": app["id"], "sourceBlobSha": sha,
        "repository": args.repository, "path": args.path, "bytes": len(raw), "githubCalls": 3}
    private_write(args.output, raw)
    private_write(args.metadata_output, json.dumps(metadata).encode())
    print(json.dumps(metadata, sort_keys=True))


if __name__ == "__main__":
    main()
