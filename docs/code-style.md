# RepoRead code style

This guide owns naming and decomposition. Spotless owns Java and Kotlin layout; Android Lint owns app diagnostics. The
repository does not add another formatter or static-analysis dependency unless repeated defects show a rule the existing
tools cannot enforce.

## Domain vocabulary

| Term | Meaning |
| --- | --- |
| repository | One connected GitHub repository. |
| document | The server-owned identity and current source metadata for one Markdown file. |
| saved page | The phone's rendered, offline copy of one document version. |
| annotation | A highlight or study card anchored to a passage. |
| review | One immutable grade event for a card. |
| library snapshot | The backend's complete response used to refresh the phone's local projection. |
| device log | A diagnostic line created and durably buffered by the Android app for backend ingestion. |

Use the same term on both sides of a boundary. Do not use `note` for server document metadata or `document` for a saved
rendered page. UI text may still say “note” when that is the user's concept.

## Names

- Name boundary identifiers for their resource: `repositoryId`, `documentId`, `annotationId`, `cardId`; never plain
  `id` when more than one identity exists in the operation.
- Name a value for its role in the current scope. Prefer `annotation`, `review`, `listedDocument` and `deletionCounts` to
  `row`, `item`, `current` and `result` in coordination code.
- `row`, `item`, `result`, `body`, `current` and similarly generic names are acceptable only in a short transformation
  where the type and role are immediately visible.
- Before validation or mapping, HTTP values are `request`, `response`, `requestBody` or `errorResponse`. Afterward, use
  the domain role they represent. Do not call a decoded object `json`.
- Booleans begin with `is`, `has`, `can`, `should`, `was` or `will`, except domain words whose boolean meaning is already explicit,
  such as `deleted`, `pending` and `bookmarked`.
- A field holding an entity is named for the entity and its role (`annotationToReattach`), not for the activity it takes
  part in (`reattaching`), which reads as a flag; the flag is `isReattaching`.
- Functions are verbs (`analyzeStudyMode`). A type is named for what it holds, not for the one screen mode that first
  used it (`OpenedReaderPage`, not `StudyPage`), and `Config` is for settings someone chose, not for the result of
  analysing input (`StudyModeAnalysis`).
- Use `_state` for a private mutable state holder exposed as `state`. Name a captured value `stateSnapshot`, not
  `current` or `now`.
- Framework-owned types say what role they implement: `Controller`, `Filter`, `Worker`, `Configuration`, `Database` and
  `Dao`. Avoid `Manager`, `Utils`, `Helper`, `Support`, `Core` and `Data` as catch-all names.
- A subclass of a framework type is named for what it adds, not just for where it is used. `ReaderSelectionWebView`
  adds the text-selection menu; `ReaderWebView` read as the reader's whole WebView, whose behavior `ReaderSession` owns.
- A caught expected boundary failure is `failure` or a qualified name such as `ioFailure`; reserve `error()` and
  assertion failures for broken invariants.
- Short mathematical names such as `i`, `j`, `m` and `n` are appropriate inside a documented algorithm. Do not expand
  them when the formula is clearer with conventional notation.

## Files and packages

- A file has one primary owner and its filename names that owner or responsibility. Check the name against the file
  list alone: someone who sees only the list should be able to guess what the file contains, and should not expect to
  find something that is in another file.
- A file named for a class or composable holds that class or composable. A file of top-level functions shared by several
  owners is named for the responsibility they share (`ReaderPageHosting.kt`: isolation, resources, the script bridge
  and the wait for rendering, used by `ReaderSession` and `IsolatedPage`). It is not named for a thing it does not
  contain: `ReaderPage.kt` read as a screen or composable.
- A screen's ViewModel and its screen composable share the screen's file (`Library.kt`, `Reader.kt`). Moving one
  screen's ViewModel into its own file would make that screen the only one laid out differently.
- A package name says what it owns. Do not introduce a generic layer solely to group unrelated shared code.
- One independently scheduled job has one worker file.
- A Compose screen owns state and effects. Extract a sibling composable when a toolbar, content region or overlay host
  has its own responsibility; do not extract every nested layout block.
- A large file is not itself a defect. Keep one coordinator when it owns ordering, locking or a cross-entity transaction.

## Control flow

- Keep the successful path left-aligned with early returns for expected terminal cases.
- Split a function when it performs sequential phases that can each be named from the domain. Do not split it to meet a
  line-count target.
- Prefer a named result type over a `Pair` when both values survive beyond a short expression or protect an invariant.
- Validate at the boundary. Once a value is validated, pass the typed value rather than its raw JSON or transport form.
- One concern catches a failure. Lower layers raise with context; higher layers decide user wording, retry or refusal.
- A fallback changes behavior only after a typed expected failure. It is not an alternate normal strategy.

## Comments

- File and type comments state ownership, invariants and exclusions. A comment that names another file or type as an
  owner must match that code (`Reader.kt` said `ReaderSession` owned the script bridge, which is in
  `ReaderPageHosting.kt`). A rename or move greps for the old name in comments as well as in code.
- Comments explain why a constraint exists, not what the next line already says.
- If a comment is needed to explain what an identifier means, first rename the identifier.

## Replacements

A rename or move updates every production caller, test, document and generated contract in the same change. Delete the
old symbol or path; do not retain aliases, forwarding wrappers or parallel vocabulary.
