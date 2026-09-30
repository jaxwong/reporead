# RepoRead — Product & Engineering Specification

> **Working title:** RepoRead  
> **Product thesis:** A mobile reading and annotation layer for developers who keep technical notes as Markdown in GitHub. GitHub remains the source of truth; RepoRead makes those notes pleasant to read, annotate, revisit, and review on a phone without modifying the original Markdown.

---

## 1. Problem

Developers often keep long-form technical notes, cheatsheets, and learning material as Markdown inside GitHub repositories because Git provides versioning, portability, searchability, and a natural workflow from an editor such as VS Code.

That workflow is strong for **writing**, but weak for **reading on a phone**.

Typical problems:

- GitHub Mobile is designed primarily as a repository/code client, not a long-form reading application.
- Rich technical Markdown is inconsistently supported on mobile, especially Mermaid diagrams.
- Readers cannot attach personal highlights and annotations to arbitrary passages without modifying the underlying Markdown.
- There is no durable "continue reading" state for long notes.
- A reader cannot easily see what changed in a note since the last time they studied it.
- Old notes become a knowledge graveyard because nothing intentionally resurfaces them.
- Existing note applications often require moving the source of truth into their own vault/workspace or using a separate sync workflow.

The core frustration is not "I need another Markdown editor."

It is:

> **I already have good technical notes in GitHub. I want a better way to read and study them on my phone.**

---

## 2. Product Vision

RepoRead treats GitHub as the canonical storage system for notes.

```text
WRITE                                  READ / STUDY

VS Code                                RepoRead Mobile
   │                                         │
   │ Markdown                               │ highlights
   │ Mermaid                                │ annotations
   │ code                                   │ progress
   ▼                                         │ review state
 GitHub  ◄───────────────────────────────────┘
 source of truth
```

RepoRead does **not** rewrite the user's notes just because the user highlighted, annotated, bookmarked, or reviewed something.

The repository contains authored knowledge.

RepoRead contains **reading state layered on top of that knowledge**.

---

## 3. Product Principles

### 3.1 GitHub remains the source of truth

RepoRead must never require users to migrate notes into a proprietary file format.

If RepoRead disappears tomorrow, all Markdown files remain intact in GitHub.

### 3.2 Reading, not authoring

The first release is optimized for consuming technical notes.

RepoRead is not an Obsidian replacement and is not a general-purpose Markdown editor.

### 3.3 Annotations must not pollute source files

Highlights, comments, bookmarks, and reading progress are stored separately from the repository.

### 3.4 Technical Markdown must render correctly

The reader should handle the content developers actually put in technical notes:

- GitHub-flavored Markdown
- fenced code blocks
- syntax highlighting
- Mermaid
- tables
- task lists
- block quotes
- inline code
- images
- links
- optional LaTeX/math in a later release

### 3.5 Git history is a feature

Because the source is Git, RepoRead should exploit document versions rather than hiding them.

Examples:

- "What changed since I last read this?"
- "This annotation was created against commit `abc123`."
- "This file moved but is still the same logical note."

### 3.6 Failure must be explicit

RepoRead must never silently attach an annotation to the wrong sentence after a document changes.

When re-anchoring confidence is insufficient, the annotation becomes `ORPHANED` and the user is shown its original context.

---

# 4. Target User

Initial target:

> Developers and technical students who keep learning notes, cheatsheets, or documentation as Markdown in GitHub and want to review them on mobile.

The first user is the developer building RepoRead.

Example repository:

```text
engineering-notes/
├── algorithms/
│   ├── binary-search.md
│   └── dynamic-programming.md
├── backend/
│   ├── spring-transactions.md
│   ├── postgres-mvcc.md
│   └── redis.md
├── go/
│   └── concurrency.md
└── infrastructure/
    └── kubernetes.md
```

---

# 5. Core User Journey

## 5.1 Connect GitHub

User signs into RepoRead and authorizes read access to selected GitHub repositories.

```text
RepoRead
   │
   ▼
Connect GitHub
   │
   ▼
Choose repositories
   │
   ▼
engineering-notes ✓
interview-notes   ✓
```

RepoRead discovers Markdown files and records their Git identity.

---

## 5.2 Browse notes

The user sees a reading-oriented view rather than a repository-oriented view.

```text
Engineering Notes

Continue Reading
────────────────────────
Spring Transactions      63%
PostgreSQL MVCC          21%

Recently Updated
────────────────────────
Go Concurrency           +34 / -12
Redis                    +18 / -4

Folders
────────────────────────
Algorithms
Backend
Go
Infrastructure
```

---

## 5.3 Read technical Markdown

A document opens as a clean mobile reading surface.

```text
Spring Transactions
━━━━━━━━━━━━━━━━━━━━━━━━━━ 63%

# Transaction Boundaries

A transaction groups several database
operations into one unit of work.

      ┌──────────────────────┐
      │     Controller       │
      │          │           │
      │          ▼           │
      │       Service        │
      │   @Transactional     │
      │          │           │
      │          ▼           │
      │      Repository      │
      └──────────────────────┘

[Mermaid rendered here]

...
```

Scroll position and logical reading progress are persisted.

---

## 5.4 Highlight and annotate

The user selects text:

```text
Spring implements declarative transactions
using a proxy around the target bean.
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
```

Actions:

```text
[ Highlight ] [ Annotate ] [ Copy ]
```

The annotation is stored by RepoRead, **not inserted into the Markdown file**.

Example annotation:

```text
Important: self-invocation does not pass
through the Spring proxy.
```

---

## 5.5 Continue later

The next time the user opens the application:

```text
Continue Reading

Spring Transactions
63% · Section: Proxy Behaviour

[ Continue ]
```

The application restores the user near the same semantic location.

---

## 5.6 Read changes since last study session

Suppose the note was last read at commit:

```text
a18fd92
```

and GitHub now contains:

```text
c814a73
```

RepoRead shows:

```text
Updated since you last read

Spring Transactions

+ New section: Transaction propagation
~ Isolation explanation rewritten
+ Diagram: proxy invocation path

[ Read changes ]
[ Read full note ]
```

This feature should be **section-aware**, rather than merely dumping a raw Git diff onto a phone.

---

# 6. MVP Scope

The MVP is deliberately narrow.

## Required

1. GitHub authentication / repository connection
2. Select one or more repositories
3. Discover Markdown files
4. Browse folders and notes
5. Render Markdown cleanly on mobile
6. Render fenced code with syntax highlighting
7. Render Mermaid
8. Cache notes for offline reading
9. Persist reading progress
10. Create highlights
11. Create textual annotations attached to highlights
12. Store annotations separately from source Markdown
13. Detect when source documents change
14. Attempt annotation re-anchoring after changes
15. Surface orphaned annotations safely
16. Show notes changed since the user last read them
17. Basic search across cached note titles/content

## Explicitly not in MVP

- Markdown editing
- Git commits from the app
- pull requests
- collaborative annotations
- AI summaries
- chat with notes
- spaced-repetition flashcards
- public social profiles
- arbitrary Git providers
- arbitrary binary document formats
- WYSIWYG editing
- team knowledge management
- comments written back into GitHub
- rich drawing/canvas tools

These may be revisited only after the reading workflow is genuinely useful.

---

# 7. Technology Choices

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

# 8. High-Level Architecture

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

# 9. Core Domain Model

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

# 10. Annotation Anchoring

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

# 11. Document Identity and File Moves

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

# 12. Repository Synchronization

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

# 13. "Changed Since Last Read"

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

# 14. Reading Progress

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

# 15. Offline-First Behaviour

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

# 16. Conflict Handling

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

# 17. Authentication and GitHub Access

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

# 18. Markdown and Mermaid Security

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

# 19. API Sketch

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

# 20. Spring Boot Package Structure

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

# 21. Database Sketch

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

# 22. Search

MVP search does not need Elasticsearch.

Use PostgreSQL full-text search on the backend or SQLite FTS through Room for cached Android content.

Search targets:

- title
- headings
- Markdown body
- annotation text

Do not introduce Elasticsearch unless actual scale or search requirements justify it.

---

# 23. Background Work

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

# 24. Architecture Decision: Modular Monolith

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

# 25. Testing Strategy

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

# 26. Observability

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

# 27. Failure Behaviour

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

# 28. Privacy

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

# 29. MVP Milestones

## Milestone 1 — Read

Goal: dogfood the application.

- Spring Boot project
- GitHub connection
- select repo
- list Markdown files
- Kotlin + Jetpack Compose Android app
- Room-backed local cache
- mobile reader
- code rendering
- Mermaid
- basic local cache

Success criterion:

> The developer prefers RepoRead over GitHub Mobile for reading his own technical notes.

## Milestone 2 — Remember

- reading progress
- bookmarks
- highlights
- annotations
- offline mutation queue

Success criterion:

> The developer can read on the phone for a week without modifying source Markdown and can always resume where he stopped.

## Milestone 3 — Survive edits

- Git version tracking
- annotation anchors
- exact/context re-anchoring
- orphan state
- manual reattachment

Success criterion:

> Common edits to notes do not destroy or incorrectly relocate annotations.

## Milestone 4 — Git-aware reading

- changed-since-last-read
- section-level diff summary
- recently changed notes

Success criterion:

> The developer can quickly review only knowledge that changed since his previous study session.

## Milestone 5 — Polish for interview

- integration tests
- metrics
- documented architecture decisions
- security review
- load/failure demonstrations
- seeded demo repository
- clean setup script

---

# 30. Recommended Interview Demo

Target: approximately 10 minutes before opening the code.

## 0:00–1:00 — Motivation

Show an actual technical Markdown note in GitHub Mobile.

Explain:

> "I write my engineering notes as Markdown in GitHub because Git is a great source of truth. But I often want to review them on my phone, where the reading experience, Mermaid support, annotations, and study state are weak."

## 1:00–2:30 — Product

Open the same repository in RepoRead.

Show:

- folder browser
- rendered Markdown
- syntax-highlighted code
- Mermaid

## 2:30–4:00 — Annotation

Highlight a sentence.

Add a margin note.

Show that the GitHub Markdown remains unchanged.

## 4:00–6:00 — Core technical problem

On laptop:

1. edit the highlighted passage;
2. insert paragraphs above it;
3. commit/push;
4. refresh RepoRead.

Show the annotation correctly re-anchoring.

Then make a destructive rewrite and show RepoRead refusing to guess:

```text
ORPHANED
```

This demonstrates a correctness decision, not merely UI.

## 6:00–7:30 — Git-aware reading

Show:

```text
Changed since you last read
```

and jump directly to a new section.

## 7:30–9:00 — Offline

Disable network.

Open cached note.

Create annotation.

Reconnect.

Show idempotent synchronization.

## 9:00–10:00 — Architecture

Briefly explain:

```text
GitHub = source
Spring Boot = durable reading/annotation state
Room / SQLite = Android offline cache
```

Then move to code.

---

# 31. Code to Show Interviewers

Prioritize code that expresses decisions.

Good candidates:

1. `AnchorResolver`
2. re-anchoring strategy implementations
3. repository incremental synchronization
4. idempotent offline mutation endpoint
5. GitHub authentication/security boundary
6. optimistic annotation updates
7. sanitization/render security configuration
8. integration tests for annotation survival

Avoid spending interview time showing generic CRUD controllers.

---

# 32. Questions to Be Ready For

- Why not just use Obsidian?
- Why GitHub instead of storing Markdown yourself?
- Why Spring Boot?
- Why a backend at all?
- Why not store annotations inside Markdown?
- How do highlights survive edits?
- Why not use line numbers?
- How do you avoid attaching an annotation to the wrong passage?
- How do you handle duplicate text?
- What happens when the entire section is rewritten?
- How do you handle file renames?
- What happens after a force-push?
- How does offline sync work?
- Can the same offline annotation be uploaded twice?
- How do you resolve concurrent annotation edits?
- Why PostgreSQL?
- Why not Elasticsearch?
- How are GitHub tokens protected?
- What repository permissions do you request?
- Can malicious Markdown execute code?
- How is Mermaid isolated?
- What is cached on the phone?
- What happens when GitHub is unavailable?
- What would fail at 100,000 users?
- What would you split out first and why?
- What part of the design are you least confident in?
- What did you deliberately not build?

---

# 33. Engineering Invariants

These should appear in `INVARIANTS.md` in the repository.

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

---

# 34. Definition of Done for the Personal Project

RepoRead is successful as a personal project when all of the following are true:

1. At least one real personal GitHub notes repository is connected.
2. A signed Android APK is installed on the developer's actual phone without requiring Google Play publication.
3. The Kotlin + Jetpack Compose client works against the real Spring Boot backend.
4. The developer uses it voluntarily for at least two weeks.
5. Mermaid diagrams render correctly.
6. Reading progress reliably resumes.
7. At least 20 real highlights/annotations have been created.
8. Source Markdown remains untouched by reading actions.
9. At least one real note edit has exercised re-anchoring.
10. Offline reading and annotation work.
11. A change made on the laptop appears in the mobile "changed since last read" workflow.
12. The backend has integration tests against PostgreSQL.
13. Important design decisions are documented as ADRs.
14. The project can be demoed locally even if GitHub is temporarily unavailable by using seeded fixtures.

The strongest interview evidence is not the feature count.

It is:

> "I built this because I needed it, installed it on my own phone, and actually used it. These engineering decisions came from the failures and edge cases I encountered while dogfooding it."
