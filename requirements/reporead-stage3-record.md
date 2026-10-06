# RepoRead — Stage 3 verification record

Evidence for the [build plan](reporead-build-plan.md)'s Stage 3 gates. Contracts and commands live in the [backend](../backend/README.md) and [Android](../android/README.md) READMEs.

**Scope decision (plan default, 2026-10-06):** offline *creation* of highlights is supported; editing and deleting are online only, with visible version conflicts. No generalized mutation replay.

## Automated (2026-10-06)

- `./gradlew :backend:test --no-daemon`: exit 0, **108 tests, 0 failures** against PostgreSQL 17.11 (Testcontainers). Annotation cases: anchor verified against the selected version's canonical text with server-derived context/headings, replay after a lost acknowledgement without a GitHub call, mutation-id reuse rejected, four concurrent duplicate submissions creating exactly one annotation/anchor/mutation, mismatched selections rejected without state, malformed requests rejected before GitHub, GitHub failure storing nothing then the same mutation succeeding, optimistic edit conflicts, versioned deletion with a late replay returning 410, per-user isolation, bookmarks not editable as highlights.
- Device tests via `adb shell am instrument` on the Pixel 8a: **9 tests, OK** — including a server list acknowledging a lost-ack pending creation by mutation id while keeping other pending creations, refused creations kept but not retried, and the platform HTTP client accepting `PATCH`.
- Room auto-migration 1→2 ran on the phone's existing database: 2 cached notes, 2 reading states, and 1 bookmark kept; `annotations` added. Flyway V3 applied to the development database; the existing bookmark row upgraded.

## On the phone and server (2026-10-06)

- **Offline creation and sync:** highlights made while the backend was unreachable (one with a note) synchronized after reconnecting as server annotations 2, 3, and 4 (18:10:14–18:10:15). One submission arrived twice and the server logged `Annotation creation replayed` for annotation 4 instead of creating a duplicate.
- **Online edit (user):** the note of annotation 3 ("caller and operation") was edited to "hello world edit" → version 2.
- **Edit conflict:** another device's edit was **simulated** by updating annotation 3 in the development database (note "SIMULATED edit from another device", version 3). The user's next edit from the phone was refused (`PATCH /api/annotations/3` → 409 `ANNOTATION_CONFLICT`, 18:26:10); the app showed both texts and the user chose **Keep mine**, saved deliberately as version 4 ("hello world edit hello", 18:26:20). The phone's row matches (version 4, not pending). Nothing was silently overwritten.
- **Defect found and fixed during Stage 3 testing:** resume failed when a Mermaid diagram was at the top of the screen; see the [Stage 2 record](reporead-stage2-record.md#defect-found-after-the-gate-2026-10-06) and `bd7f4e7`.
- **Source integrity:** all GitHub calls from the backend are GETs (blob reads verify anchors); no endpoint writes to GitHub.

## Not yet verified on the real system

- The user's explicit observation that highlights and a note are redrawn in place after reopening the note (3A gate wording), and the cross-block selection refusal on the phone.
- A pending creation surviving process death before sync (the offline highlights synced, but a force-stop while pending was not separately reported).
- A server refusal of a real offline creation (covered by automated tests only).
