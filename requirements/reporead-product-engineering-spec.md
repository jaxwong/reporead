# RepoRead — Implementation Specification

This document owns the technical design for the MVP: architecture, data ownership, synchronization, anchoring, APIs, security, failure behavior, and verification. It does not define feature priority or user-facing acceptance criteria.

- [User stories](reporead-user-stories.md) define the user need and observable acceptance criteria.
- [Feature list](reporead-feature-list.md) defines MVP scope, delivery order, and project-level success criteria.

GitHub owns authored Markdown; the Spring Boot backend owns durable reading and annotation state; the Android app owns its offline cache and pending mutations. Reading actions must not write back to source Markdown.

---

# 1. Technology Choices

## Backend

- Java 25
- Spring Boot 4.x
- Spring MVC
- Spring Security
- PostgreSQL
- Flyway
- Spring Data JPA for RepoRead's own relational state
- JDBC where lower-level control is useful
- GitHub REST/GraphQL API
- Micrometer / Actuator
- Testcontainers
- Docker Compose for local development

### Client/Backend Language Boundary

Use **Kotlin for Android** and **Java for Spring Boot**.

This is deliberate rather than accidental:

- Kotlin is chosen because the client is a native Android application.
- Java is chosen because the backend learning goal is to understand Spring Boot deeply without introducing Kotlin-specific Spring behavior at the same time.
- The languages meet only through versioned API contracts.
- DTOs are defined separately on both sides rather than shared as a common JVM module.

This avoids coupling mobile releases to backend implementation classes and makes contract compatibility an explicit engineering concern.

## Android Mobile Client

Recommended for the first release:

- Kotlin
- Jetpack Compose
- Coroutines
- Room for local persistence
- SQLite through Room for offline metadata/cache
- Android Keystore-backed secure storage for sensitive local credentials/session material
- WorkManager for deferred/background synchronization where appropriate
- WebView-based technical Markdown/Mermaid rendering only where native rendering is not practical

Reasoning:

RepoRead is intentionally **Android-first**. The product is fundamentally a mobile reading experience, and native Android gives direct control over text selection, scrolling, lifecycle behavior, offline persistence, background work, secure storage, and platform integration.

Jetpack Compose keeps the UI model modern and declarative, while Kotlin is the primary language for contemporary Android development.

The backend remains **Java + Spring Boot** rather than Kotlin. This keeps the main backend learning objective focused on Spring Boot and modern Java while still giving the project a real native-mobile boundary.

The client and backend must communicate through explicit HTTP/JSON contracts. Do not share JVM domain classes between the Android app and Spring Boot simply because both run on the JVM. Keeping that boundary explicit forces API validation, versioning, compatibility, and failure behavior to be designed deliberately.

iOS is explicitly out of scope for the MVP. Cross-platform support should be reconsidered only after the Android product is genuinely useful.

## Android Installation and Dogfooding

RepoRead does **not** need to be published to Google Play for the developer to use it personally.

During development:

```text
Mac / development machine
        │
        │ Android Studio / adb
        ▼
Physical Android phone
```

For regular personal use, create a signed release APK and install it directly on the device.

```text
RepoRead APK
    │
    ▼
Android phone
    │
    ▼
normal installed app
```

This means the project can be dogfooded from the earliest usable milestone without waiting for store review or public distribution.

Publishing to Google Play is a separate future decision and is out of scope for the MVP.

The expected development loop is:

```text
1. Build feature
2. Install/update APK on the real phone
3. Use RepoRead during normal free time
4. Record actual friction
5. Let real usage drive the next engineering decision
```

A physical Android device is the primary target. Emulator support is useful for development, but the product is considered validated only when it works reliably on the developer's actual phone.

## Rendering

Candidate approach:

```text
Markdown
   │
   ▼
Parser
   │
   ├── headings
   ├── paragraphs
   ├── code
   ├── tables
   └── mermaid blocks
   │
   ▼
Sanitized render model
   │
   ▼
Mobile renderer / isolated WebView
```

Mermaid must be executed in a controlled rendering context.

Raw repository HTML must never be blindly executed.

---

# 2. High-Level Architecture

```text
                        GitHub
                           │
                    API / Webhooks
                           │
                           ▼
                 ┌──────────────────┐
                 │   Spring Boot    │
                 │                  │
                 │ authentication   │
                 │ repo sync        │
                 │ document state   │
                 │ annotations      │
                 │ re-anchoring     │
                 │ reading state    │
                 │ change tracking  │
                 └────────┬─────────┘
                          │
                          ▼
                      PostgreSQL

                          ▲
                          │ HTTPS
                          ▼

                 ┌──────────────────┐
                 │  Android Client  │
                 │ Kotlin + Compose │
                 │                  │
                 │ reader           │
                 │ Room cache       │
                 │ offline state    │
                 │ text selection   │
                 │ Mermaid          │
                 └────────┬─────────┘
                          │
                          ▼
                    Room / SQLite
```

The backend owns durable user/application state.

The Android application owns an offline Room cache and pending local mutations.

GitHub owns the Markdown source.

---

# 3. Core Domain Model

## User

```text
User
- id
- githubUserId
- username
- createdAt
```

## RepositoryConnection

```text
RepositoryConnection
- id
- userId
- githubRepositoryId
- owner
- name
- defaultBranch
- lastSyncedAt
- status
```

## Document

A logical Markdown file.

```text
Document
- id
- repositoryConnectionId
- path
- title
- currentBlobSha
- currentCommitSha
- lastSyncedAt
- deletedAt?
```

## DocumentVersion

A particular Git version of a document.

```text
DocumentVersion
- id
- documentId
- blobSha
- commitSha
- contentHash
- capturedAt
```

RepoRead does not need to persist every historical Markdown body forever during MVP. Old source can often be retrieved from GitHub by commit/blob when required.

## ReadingState

```text
ReadingState
- userId
- documentId
- lastReadBlobSha
- progressPercent
- anchor
- lastReadAt
```

The `anchor` should identify a semantic location rather than relying solely on a pixel scroll offset.

## Annotation

```text
Annotation
- id
- userId
- documentId
- sourceBlobSha
- type
- note
- color
- status
- createdAt
- updatedAt
```

Possible types:

```text
HIGHLIGHT
ANNOTATION
BOOKMARK
```

Possible statuses:

```text
ANCHORED
REANCHORED
ORPHANED
DELETED
```

## AnnotationAnchor

```text
AnnotationAnchor
- annotationId
- exactText
- prefixText
- suffixText
- startOffset
- endOffset
- headingPath
- sourceBlobSha
```

Example:

```json
{
  "exactText": "Spring implements declarative transactions using a proxy",
  "prefixText": "By default,",
  "suffixText": "around the target bean.",
  "startOffset": 4182,
  "endOffset": 4237,
  "headingPath": [
    "Spring Transactions",
    "Proxy Behaviour"
  ],
  "sourceBlobSha": "8f17aa..."
}
```

---

# 4. Annotation Anchoring

This is one of the project's core engineering problems.

## Problem

An annotation created against version A may become invalid when the Markdown is edited into version B.

A simple character offset is insufficient.

Example:

Version A:

```text
A transaction ensures that multiple database
operations either succeed or fail together.
```

Version B:

```text
A database transaction groups multiple operations
into a single unit of work: they commit together
or the entire unit rolls back.
```

The original character offsets no longer point to the same concept.

---

## Re-anchoring Strategy

Use a staged algorithm.

### Stage 1 — Exact position

If:

```text
document[startOffset:endOffset] == exactText
```

keep the existing anchor.

Confidence: `1.0`

### Stage 2 — Exact quote search

Search the new document for `exactText`.

If exactly one occurrence exists, move the anchor there.

Confidence: high.

### Stage 3 — Context-assisted exact match

If the same exact text appears multiple times, use:

- prefix
- suffix
- heading path

to select the correct occurrence.

### Stage 4 — Fuzzy match

If the text was edited slightly:

1. search within the previous heading/section first;
2. find candidate text spans;
3. score text similarity;
4. score prefix/suffix similarity;
5. score heading similarity;
6. choose only if confidence exceeds a conservative threshold.

Example scoring concept:

```text
score =
    0.55 * selectedTextSimilarity
  + 0.20 * prefixSimilarity
  + 0.15 * suffixSimilarity
  + 0.10 * headingSimilarity
```

Exact weights are an implementation detail and should be measured rather than assumed.

### Stage 5 — Orphan

If no candidate is trustworthy:

```text
status = ORPHANED
```

Never attach the note somewhere merely because it is the "closest" text.

UI:

```text
⚠ This annotation could not be reliably
reattached after the note changed.

Original selection:
"Spring implements declarative transactions..."

[ View current section ]
[ Reattach manually ]
```

---

# 5. Document Identity and File Moves

Paths are not stable identities.

A user may move:

```text
backend/spring.md
```

to:

```text
java/spring/transactions.md
```

RepoRead should attempt to preserve logical identity.

MVP strategy:

1. same path → same document
2. changed path with same Git blob SHA → rename/move
3. changed path with highly similar contents within the same sync window → probable rename
4. otherwise create a new document and mark the original deleted

Do not automatically merge documents with weak similarity.

---

# 6. Repository Synchronization

## Initial Sync

```text
GitHub
   │
   ▼
repository tree
   │
   ▼
filter *.md
   │
   ▼
Document records
   │
   ▼
fetch metadata/content as required
```

Avoid downloading unnecessary repository blobs.

## Incremental Sync

Track:

```text
lastSyncedCommitSha
```

When the branch advances:

```text
old commit
   │
   ▼
Git comparison
   │
   ├── added
   ├── modified
   ├── renamed
   └── deleted
```

Process only changed Markdown files.

## Triggering

MVP options:

- explicit pull-to-refresh;
- refresh on application open with a freshness window.

Later:

- GitHub webhook updates the backend immediately after pushes.

A webhook should be treated as a notification that "something changed", not as the only source of truth. Sync should still reconcile against GitHub.

---

# 7. "Changed Since Last Read"

This is a core differentiator.

Reading state stores:

```text
lastReadBlobSha
```

Current document stores:

```text
currentBlobSha
```

If they differ:

```text
lastReadBlobSha != currentBlobSha
```

RepoRead computes the change.

## First implementation

Use Git diff to identify changed line ranges.

Map those ranges to Markdown heading sections.

Instead of:

```text
@@ -121,8 +132,19 @@
```

show:

```text
Updated since you last read

+ Transaction Propagation
~ Isolation Levels
+ New Mermaid diagram
```

Selecting a section opens the current document at that location.

## Later improvement

AST-aware Markdown differencing can distinguish:

- heading added
- paragraph changed
- code block changed
- Mermaid block changed
- section deleted

---

# 8. Reading Progress

Pixel position alone is fragile because rendering changes across:

- phone sizes
- font sizes
- code wrapping
- Mermaid dimensions
- document edits

Store a semantic reading anchor.

Possible shape:

```json
{
  "headingPath": [
    "Spring Transactions",
    "Proxy Behaviour"
  ],
  "textPrefix": "Spring implements declarative",
  "approximateBlockIndex": 43
}
```

Also store:

```text
progressPercent
```

for presentation.

When restoring:

1. locate heading;
2. locate text prefix if available;
3. fall back to block index;
4. finally fall back to approximate percentage.

---

# 9. Offline-First Behaviour

Reading should work on a train without connectivity.

## Cached locally

- repository metadata
- document list
- recently/opened Markdown bodies
- rendered-resource metadata
- reading progress
- annotations
- pending annotation mutations

## Mutation queue

If offline:

```text
Create annotation
       │
       ▼
Room / SQLite
status = PENDING_SYNC
       │
       ▼
network returns
       │
       ▼
POST /annotations
       │
       ▼
status = SYNCED
```

Every mutation gets a client-generated UUID to make retries idempotent.

Example:

```text
mutationId = 01K7...
```

The server must treat repeating the same mutation as the same operation.

---

# 10. Conflict Handling

For MVP, assume annotations are edited by one user across potentially several devices.

Use optimistic concurrency.

```text
Annotation
- version
```

Update request:

```text
PATCH /annotations/{id}

expectedVersion: 4
```

If the server contains version 5:

```text
409 CONFLICT
```

The client can fetch the latest value and ask the user which text to retain.

Reading progress uses simpler last-write-wins semantics because losing an exact progress update is low impact.

Annotation text does **not** silently use last-write-wins.

---

# 11. Authentication and GitHub Access

Security principle:

> Request the minimum GitHub permissions required to read selected repositories.

Preferred architecture:

- GitHub App rather than a broad permanent personal access token;
- installation scoped to repositories chosen by the user;
- repository contents: read-only;
- metadata: read-only.

RepoRead's backend handles GitHub access.

Do not embed long-lived GitHub credentials inside the mobile bundle.

Sensitive tokens stored by the backend must be encrypted at rest and excluded from logs.

---

# 12. Markdown and Mermaid Security

Repository content is untrusted input.

Threats include:

- raw HTML
- script injection
- malicious URLs
- SVG payloads
- Mermaid security issues
- unexpectedly expensive diagrams
- oversized files

Rules:

1. parse Markdown with raw HTML disabled by default;
2. sanitize generated HTML;
3. allow only expected URL schemes;
4. render Mermaid with strict security configuration;
5. isolate Mermaid execution from the native mobile context;
6. enforce maximum source and diagram sizes;
7. do not expose authentication tokens to rendering JavaScript;
8. block arbitrary `javascript:` links;
9. treat remote images as external content.

---

# 13. API Sketch

## Repository

```text
GET    /api/repositories
POST   /api/repositories/{githubRepoId}/connect
DELETE /api/repositories/{id}
POST   /api/repositories/{id}/sync
```

## Documents

```text
GET /api/repositories/{id}/documents
GET /api/documents/{id}
GET /api/documents/{id}/content
GET /api/documents/{id}/changes-since-read
```

## Reading state

```text
GET /api/documents/{id}/reading-state
PUT /api/documents/{id}/reading-state
```

## Annotations

```text
GET    /api/documents/{id}/annotations
POST   /api/documents/{id}/annotations
PATCH  /api/annotations/{id}
DELETE /api/annotations/{id}
POST   /api/annotations/{id}/reattach
```

## Search

```text
GET /api/search?q=transaction+proxy
```

---

# 14. Spring Boot Package Structure

Package by capability rather than by technical layer.

```text
com.reporead
├── auth/
│   ├── GitHubAuthenticationService
│   └── ...
│
├── repository/
│   ├── RepositoryController
│   ├── RepositoryService
│   ├── GitHubRepositoryClient
│   └── ...
│
├── document/
│   ├── DocumentController
│   ├── DocumentService
│   ├── DocumentVersionService
│   └── ...
│
├── sync/
│   ├── RepositorySyncService
│   ├── DiffService
│   └── ...
│
├── annotation/
│   ├── AnnotationController
│   ├── AnnotationService
│   ├── AnnotationAnchor
│   └── ...
│
├── anchoring/
│   ├── AnchorResolver
│   ├── ExactAnchorStrategy
│   ├── ContextAnchorStrategy
│   ├── FuzzyAnchorStrategy
│   └── ...
│
├── reading/
│   ├── ReadingStateController
│   └── ReadingStateService
│
├── search/
│   └── ...
│
└── infrastructure/
    ├── github/
    ├── persistence/
    └── security/
```

The important boundary:

```text
annotation/       owns what an annotation means
anchoring/        owns how an annotation is located in a changing document
sync/             owns reconciliation with GitHub
infrastructure/   owns external-system details
```

## Android Package Structure

Use feature-oriented packages rather than one giant `ui/` folder.

```text
com.reporead.android
├── auth/
├── library/
├── reader/
├── annotation/
├── changes/
├── search/
├── sync/
├── data/
│   ├── local/
│   │   └── Room database / DAOs
│   └── remote/
│       └── RepoRead API client
└── core/
    ├── model/
    ├── network/
    └── security/
```

Keep Android concerns on the client:

```text
Compose UI
Room
WorkManager
Android lifecycle
secure local storage
text selection
```

Keep source-of-truth and cross-device concerns on the backend:

```text
GitHub synchronization
document versions
annotation durability
annotation re-anchoring
conflict detection
changed-since-last-read
```

---

# 15. Database Sketch

```text
users
-----
id
github_user_id
username
created_at


repository_connections
----------------------
id
user_id
github_repository_id
owner
name
default_branch
last_synced_commit_sha
last_synced_at
status


documents
---------
id
repository_connection_id
path
title
current_blob_sha
current_commit_sha
last_synced_at
deleted_at


document_versions
-----------------
id
document_id
blob_sha
commit_sha
content_hash
captured_at


reading_states
--------------
user_id
document_id
last_read_blob_sha
progress_percent
anchor_json
last_read_at
version


annotations
-----------
id
user_id
document_id
source_blob_sha
type
note
color
status
version
created_at
updated_at


annotation_anchors
------------------
annotation_id
exact_text
prefix_text
suffix_text
start_offset
end_offset
heading_path_json
source_blob_sha
confidence
```

Indexes should follow measured queries, but likely include:

```text
documents(repository_connection_id, path)
annotations(user_id, document_id)
reading_states(user_id, last_read_at)
repository_connections(user_id)
```

---

# 16. Search

MVP search does not need Elasticsearch.

Use PostgreSQL full-text search on the backend or SQLite FTS through Room for cached Android content.

Search targets:

- title
- headings
- Markdown body
- annotation text

Do not introduce Elasticsearch unless actual scale or search requirements justify it.

---

# 17. Background Work

Examples:

- repository synchronization
- document diff calculation
- annotation re-anchoring
- search indexing
- stale GitHub data refresh

MVP can use database-backed work + Spring scheduling.

Do not add Kafka merely to make the architecture look distributed.

The first version is one Spring Boot deployment.

---

# 18. Architecture Decision: Modular Monolith

RepoRead should begin as a modular monolith.

Reasons:

- one developer;
- one operational unit;
- strong transactions around application state;
- easy local debugging;
- feature boundaries can still be explicit;
- no demonstrated need for independent deployment.

```text
Android
Kotlin + Compose
  │
  ▼
Spring Boot
  ├── repository
  ├── document
  ├── annotation
  ├── anchoring
  ├── reading
  └── search
  │
  ▼
PostgreSQL
```

Potential future separation should be driven by evidence, not aesthetics.

---

# 19. Testing Strategy

## Unit tests

Good candidates:

- exact anchor resolution
- context disambiguation
- fuzzy matching
- confidence thresholds
- heading extraction
- progress-anchor restoration
- sanitization rules

Example invariant:

```text
If re-anchoring confidence is below threshold,
the annotation MUST become orphaned rather than
attach to an uncertain location.
```

## Property-style tests

Generate document edits such as:

- insert paragraph before annotation
- move section
- duplicate selected phrase
- edit selected phrase slightly
- remove selected phrase

Verify anchoring invariants.

## Integration tests

Use Testcontainers with PostgreSQL.

Test:

- repository synchronization persistence
- optimistic locking
- mutation idempotency
- annotation transaction boundaries
- migration correctness

## GitHub integration tests

Use recorded fixtures or a dedicated test repository where appropriate.

Do not make the entire test suite dependent on live GitHub network calls.

## Android tests

Use unit tests plus Compose UI/instrumentation tests where platform behavior matters.

Test:

- offline document open from Room
- pending annotation persistence
- reconnect synchronization
- duplicate offline mutation replay
- rendered code
- Mermaid rendering
- text selection/highlight creation
- restore reading position
- lifecycle recreation
- orphaned-annotation UX
- WorkManager retry behavior where used

---

# 20. Observability

Expose with Spring Boot Actuator / Micrometer:

```text
reporead_github_sync_total
reporead_github_sync_failures_total
reporead_github_api_requests_total
reporead_annotations_created_total
reporead_annotation_reanchor_total
reporead_annotation_reanchor_orphaned_total
reporead_annotation_reanchor_duration
reporead_documents_synced_total
```

Structured logs should include correlation IDs but never:

- GitHub access tokens
- authorization headers
- private Markdown bodies by default
- annotation contents unless explicitly required for debugging

Useful operational questions:

- Is GitHub sync failing?
- Are we approaching GitHub rate limits?
- Did a new anchoring release increase orphan rates?
- Are sync jobs becoming slower?
- Which repository sync failed and why?

---

# 21. Failure Behaviour

## GitHub unavailable

- cached notes remain readable;
- annotations continue working offline;
- sync status shows stale;
- retries use backoff.

## GitHub rate limit reached

- stop aggressive retries;
- expose next retry time;
- preserve cached content.

## Document deleted upstream

- keep annotations/history locally;
- mark document deleted;
- do not silently destroy the user's reading state.

## Document radically rewritten

- attempt conservative re-anchoring;
- orphan uncertain annotations.

## Backend unavailable

- cached notes remain readable;
- local annotations queue for later sync.

---

# 22. Privacy

RepoRead may access private repositories containing sensitive technical notes.

Requirements:

- request only repository access the user explicitly grants;
- do not expose repository content across users;
- tokens encrypted at rest;
- repository content excluded from analytics;
- repository content excluded from logs;
- deleting a repository connection removes cached backend content and associated synchronization credentials;
- provide a clear account deletion path.

---

# 23. Engineering Invariants

These invariants are normative for the MVP.

## Source integrity

> RepoRead reading actions must never modify source Markdown.

## Annotation correctness

> RepoRead must never silently attach an annotation to a location it cannot identify with sufficient confidence.

## GitHub isolation

> A user may access only repositories explicitly authorized for that user.

## Token secrecy

> GitHub access credentials must never appear in logs, rendered Markdown, analytics, or mobile JavaScript.

## Offline idempotency

> Replaying the same client mutation ID must not create duplicate annotations.

## Source traceability

> Every annotation must retain the Git blob/version against which it was originally created.

## Graceful degradation

> Previously cached notes must remain readable when GitHub or RepoRead's backend is temporarily unavailable.
