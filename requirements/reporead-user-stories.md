# RepoRead — User Stories

> **Product thesis:** Developers who write technical Markdown in GitHub need a better way to read, annotate, and revisit those notes on an Android phone. GitHub remains the source of truth; RepoRead stores reading state separately.

For scope, priority, and delivery order, see the [feature list](reporead-feature-list.md). For architecture, contracts, and engineering rules, see the [implementation spec](reporead-product-engineering-spec.md).

## Problem and audience

The initial user is a developer or technical student with learning notes, cheatsheets, or documentation in GitHub. Writing in an editor and storing Markdown in Git already works well. Reading long notes on a phone does not: technical Markdown can render poorly, personal annotations would alter source files, reading position is lost, changes are hard to review, and old notes are easy to forget.

The first user is the developer building RepoRead, using a real personal notes repository on a physical Android phone. A typical repository has folders such as `algorithms/`, `backend/`, and `infrastructure/`, with Markdown notes inside them.

The core need is: **“I already have good technical notes in GitHub. I want a better way to read and study them on my phone.”** RepoRead is a reading and study layer, not a Markdown editor or a replacement vault.

## Product rules across all stories

- GitHub owns authored Markdown. Reading, highlighting, bookmarking, annotating, and reviewing in RepoRead never modify it.
- RepoRead owns durable reading and annotation state; the Android app keeps an offline copy and pending local changes.
- The reader supports technical content, including GitHub-flavored Markdown, fenced code, tables, task lists, links, images, and Mermaid. Math is a later possibility, not an MVP promise.
- A stale annotation must not be silently attached to the wrong passage. When its location cannot be trusted, show it as orphaned with its original context.
- Previously cached notes remain readable when GitHub or the RepoRead backend is temporarily unavailable.

## MVP stories

### US-01 — Connect selected GitHub repositories

**As a** developer with notes in GitHub, **I want** to grant RepoRead read access to selected repositories, **so that** I can use my existing notes without migrating them.

**Acceptance criteria**

- I can sign in, see repositories available to connect, and select one or more of them.
- RepoRead discovers Markdown notes in connected repositories and preserves their folder paths.
- A repository that I did not authorize is not exposed to my account.
- RepoRead never needs write access to the repository for reading workflows.

### US-02 — Browse a reading-oriented library

**As a** reader, **I want** to browse folders and notes, **so that** I can find a note without navigating a code-oriented repository UI.

**Acceptance criteria**

- I can browse connected repositories, folders, and Markdown notes.
- Continue-reading and recently-updated notes are visible when that data exists.
- An empty repository or folder has a clear empty state rather than appearing broken.

### US-03 — Read technical Markdown on a phone

**As a** reader of engineering notes, **I want** a clean mobile view of technical Markdown, **so that** code, diagrams, and prose remain usable on my phone.

**Acceptance criteria**

- The note renders headings, paragraphs, links, images, tables, task lists, block quotes, inline code, and fenced code with syntax highlighting.
- Mermaid blocks render as diagrams without granting repository content access to app credentials or native APIs.
- Oversized, unsupported, or unsafe content is handled visibly rather than executed as arbitrary HTML or script.

### US-04 — Continue where I stopped

**As a** reader of long notes, **I want** my reading position saved, **so that** I can resume near the same passage later, even when layout changes.

**Acceptance criteria**

- Closing and reopening a note restores a semantic location near where I stopped, not only a pixel offset.
- The library can show approximate progress and a Continue action.
- I can bookmark a note without modifying its Markdown.
- If the note changed, RepoRead makes a best-effort restore and does not claim an exact location it cannot identify.

### US-05 — Highlight and annotate without editing source

**As a** student, **I want** to highlight a passage and optionally add my own note, **so that** I can study it without changing the repository Markdown.

**Acceptance criteria**

- I can select text, create a highlight, and attach a textual annotation to it.
- My highlight or annotation can be viewed again in its document context.
- The source Markdown and Git history are unchanged by these actions.
- Each annotation retains the source version and original selected context.

### US-06 — Read and annotate offline

**As a** commuter, **I want** cached notes and my annotations available without connectivity, **so that** I can keep reading and studying on a train.

**Acceptance criteria**

- A previously cached note opens when GitHub or the backend is unavailable, with a visible stale/offline state.
- A highlight or annotation created offline remains visible locally and is pending synchronization.
- Reconnecting synchronizes a pending mutation without creating duplicates, including when the same mutation is sent again.
- An unresolved edit conflict in annotation text is surfaced to me; it is not silently overwritten.

### US-07 — Keep annotations trustworthy after edits

**As a** reader whose source notes evolve, **I want** annotations to follow the same passage when possible, **so that** normal edits do not destroy my study notes.

**Acceptance criteria**

- Inserting content before a passage does not leave its annotation attached to an old character offset.
- If a passage is moved or lightly edited and its identity is sufficiently clear, the annotation is re-anchored.
- If duplicate passages or a rewrite make the location uncertain, the annotation becomes orphaned instead of attaching to a guess.
- I can see the original selection/context and manually reattach an orphaned annotation.

### US-08 — Preserve a note through a file move

**As a** repository owner who reorganizes folders, **I want** RepoRead to recognize a moved note when it can, **so that** my reading state and annotations stay with the logical document.

**Acceptance criteria**

- A path-only move with the same Git blob retains the logical document identity.
- A probable rename with changed content is accepted only when evidence is strong enough; a weak match does not merge unrelated notes.
- A genuinely deleted note is marked deleted without silently destroying its reading history or annotations.

### US-09 — Review what changed since I last read

**As a** returning reader, **I want** to see which notes and sections changed since my last study session, **so that** I can focus on new or revised material.

**Acceptance criteria**

- RepoRead compares the version I last read with the current version.
- The library identifies notes updated since I read them.
- A note shows a mobile-friendly section-level change summary and lets me open a changed section or the full note.
- A note I have never read does not pretend to have a “since last read” baseline.

### US-10 — Search my notes

**As a** reader, **I want** basic search across my cached notes, **so that** I can return to a topic without remembering its folder.

**Acceptance criteria**

- I can search cached note titles and content and open a matching note.
- An empty query or no matches has a clear state.
- Search does not expose another user's private repository content.

### US-11 — Control my connected data

**As a** user with possibly private notes, **I want** to disconnect a repository or delete my account, **so that** RepoRead does not retain access or content I no longer want it to hold.

**Acceptance criteria**

- I can see and disconnect a connected repository.
- Disconnecting removes backend cached repository content and associated synchronization credentials, without modifying GitHub source.
- There is a clear account-deletion path.

## Later, not MVP

The MVP does not include Markdown editing, Git commits or pull requests from the app, collaborative annotations, AI summaries or chat, spaced-repetition flashcards, public social profiles, other Git providers, arbitrary binary documents, WYSIWYG editing, team knowledge management, comments written to GitHub, or rich drawing tools. Optional math rendering and webhook-driven refresh can be reconsidered after the core reading workflow is useful.
