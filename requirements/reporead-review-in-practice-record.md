# Review in Practice — record

Branch `review-in-practice` (from `practice-session` at b5d0015, PR #20), 2026-10-14. The user found review cards not intuitive and approved moving review into the Practice tab.

## What was confusing

- Review was a small **Review cards** button above the Library tabs, apart from Practice, and nothing said which held what.
- Nothing said when cards were due: Review showed only the next session's size, so an empty one looked broken.
- A card whose note changed was left out of every session with "Check this card…"; the fix (open the note, its highlights panel, **Answer is still right**) was three screens away.
- Review used another flow than Practice: **Start review**, **Reveal answer**, no repeat of a miss in the session.

## What changed

- `library/Review.kt`: `dueSession` now includes due cards that need checking (to be fixed in place); it leaves out cards the server refused or whose note was removed, which only Notebook can deal with. `reviewOutlook` counts due and blocked cards, or finds the next day with cards due and how many.
- `library/Notebook.kt`: `ReviewScreen` is a session like Practice's: it starts on opening, shows the question alone, then **Show answer**, the answer and the grades under it. A card's first grade in the session is saved; a grade below Good brings it back unsaved (SM-2 step 7). The log since the session started, not the queue, says which grades are saved, so recreation never saves one twice. `FixCard` shows a due card's fix in place: when its note changed and the server has placed its answer in the new version, that answer and **Answer is still right** (`Sync.checkCard`, the existing endpoint, so online only; confirmed, the card goes to the back of the session); otherwise what to open (the note, to save its current version or to reattach the answer) or that Library must sync first. `outlookLine` writes the one-line summary.
- `library/Practice.kt`, `library/Library.kt`: the Practice tab's first row is **Review** with that line; the Practice tab icon shows the due count; the **Review cards** button is removed.
- Sync: no new path. Library already runs the full sync (pending cards and grades, then the notebook and the server's session size) whenever it opens while signed in, including on return from a session. Review says to open Library online only before the first sync has delivered the session size.

## Verification

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --no-daemon
```

Exit **0**. Unit tests include the replaced rule (blocked due cards join a session, refused/removed ones do not), the outlook, and its line.

Device (Pixel 8a, `am instrument`): **OK (39 tests)**, twice. `ReviewLifecycleTest`: ten offline grades persisting once across recreation; a missed card coming back without a second saved grade; a card checked in an older version showing its answer and no grades, and **Answer is still right** against an unreachable server (closed local port) leaving it unconfirmed with the message. The first run failed only because the test expected the raw error text instead of the app's wording.

Real data (debug build, nothing graded or confirmed): the Practice tab shows **Review · Nothing due · next on Thu 15 Oct, 1 card** (a card the user graded on the release build) and the topics with "1 on your schedule" in `core/backend engineering`.

Not verified: a successful **Answer is still right** against the real server, and the tab's due badge (no card was due on the phone).
