# RepoRead interview bank contract

This is what RepoRead's Practice tab reads from this repository. Inside a bank, prose outside any `###` is free-form and RepoRead ignores it; every `###` must follow the question shape below exactly.

## Banks

1. **A bank is any `.md` file in a folder named exactly `interview`.** `interviews/` and `interview.md` are not banks. The folder holding `interview/` is the Practice topic (`core/networking/interview/` → `core/networking`). A bank with no `###` (an orientation page) has no questions.
2. **Every `###` in a bank is a question.** It runs until the next `#`, `##` or `###`. A leading `N. ` on the heading is dropped when shown.
3. **No two Canonical lines in one file have the same text.** The text is compared as displayed: link text counts, link targets do not. `[Count the round trips](a.md#x)` and `[Count the round trips](b.md#y)` are the same line. Different files may repeat a line.

## The question shape

A question is these blocks, in this order, and nothing else:

| # | Block | Required | Form |
| --- | --- | --- | --- |
| 1 | Prompt material | No | Paragraphs, lists and code blocks between the `###` and the first label |
| 2 | `**Interviewer is testing:**` | Yes | The label and its text in one paragraph |
| 3 | `**A complete answer should:**` | Yes | The label alone in its paragraph, then one `-` bullet list: one point per item, each a verb phrase (name, explain, trace…), no nested bullets |
| 4 | `**Worked answer:**` | No | The label and a link to a worked solution, in one paragraph |
| 5 | `**Short answer:**` | Yes | The label and the answer in one paragraph: one to three sentences that answer the question on their own |
| 6 | `**Canonical answer:**` or `**Canonical path:**` | Yes | The label and the links to the sections that answer the question, in one paragraph; always the last block |

- A label is bold with the colon inside, spelled and capitalised exactly as above. A paragraph that starts with bold text ending in a colon is read as a label, so prompt material must not start a paragraph that way (`Evidence:` is fine, `**Evidence:**` is not).
- After the first label, the only non-label block allowed is the checklist's list. No `---` rules, `####` headings, unlabelled paragraphs, tables or code inside a question; put that material in the linked note.
- The Canonical line links each section as `[Text](../note.md#heading)` or `[[note#heading]]`, pointing at a note that exists in this repository. Its text names the sections; the Short answer is what answers.
- Under `Canonical path:`, the top-level list items of the prompt material are asked together with the heading, numbered.

## What shows as a warning

Practice lists these under the topic as "headings are not questions" and does not ask them. Nothing is dropped silently.

| Warning | Cause | Fix |
| --- | --- | --- |
| A `###` heading, with the rule it breaks | A required block is missing, a label is unknown or misspelled, blocks are out of order, the checklist is inline instead of bullets, or something other than the checklist's list follows the first label | Rewrite the question to the shape above |
| A `###` heading | More than one Canonical line (questions written as `####` under one `###`) | Give each question its own `###` |
| A Canonical line | The line is not under any `###` (before the first one, or straight under a `##`) | Move it under its question's `###` |
| `… — repeats a Canonical line` | Its line has the same text as an earlier question's line in this file; the earlier one is kept | Change **this** question's line (see Editing) |

## How a question is shown

| In the bank | In Practice |
| --- | --- |
| `##` heading | The level label (Foundations, Applied questions, …); a leading `N. ` is dropped |
| `###` heading | The question; a leading `N. ` is dropped |
| Prompt material | With the question, before **Show answer**; under `Canonical path:` its top-level list items are numbered and nested bullets are hidden |
| `Short answer:` | The answer, first after **Show answer** |
| `Interviewer is testing:` and the checklist | Under the Short answer, as the standard to grade against |
| `Worked answer:` | A link under the checklist |
| The Canonical line | **Read linked section**: each linked section, from the saved note |

## Template

````markdown
# 01 — Processes, Servers, Ports, and Sockets — Interview Questions

## 1. Foundations

### 1. What is a socket, and how is it different from a port and a connection?

**Interviewer is testing:** Whether you can separate the three ideas.

**A complete answer should:**

- define a port, a socket and a connection
- give one example of how the three relate

**Short answer:** A port is a number that picks one service on a host. A socket is the operating system's endpoint for one side of the traffic, bound to an address and a port. A connection is the pair of sockets at its two ends.

**Canonical answer:** [The operating-system boundary](../01-processes-servers-ports-and-sockets.md#the-operating-system-boundary)

### 2. A client reports "connection refused" on port 8080, but the process is running.

Evidence:

```text
$ ss -ltn 'sport = :8080'
State  Recv-Q Send-Q Local Address:Port
LISTEN 0      511    127.0.0.1:8080
```

**Interviewer is testing:** Whether you read the bind address, not just the port.

**A complete answer should:**

- notice the listener is bound to `127.0.0.1`
- explain why a remote client is refused
- give the fix: bind to `0.0.0.0` or the host's address

**Short answer:** The server listens only on loopback, so connections from other hosts are refused; bind it to an address those clients can reach.

**Canonical answer:** [Binding an address](../01-processes-servers-ports-and-sockets.md#binding-an-address)

## 3. Design follow-ups

### After you draw the request path

1. Which layer does each box on the path read, and which one terminates TLS?
2. How many round trips does a cold request from your farthest user cost?

**Interviewer is testing:** Whether you can walk a request through each box and count what it costs.

**A complete answer should:**

- name the layer each box reads
- say which box terminates TLS
- count the round trips for DNS, TCP, TLS and the request itself

**Short answer:** Each box reads only up to its own layer, and TLS ends at the first box that must read HTTP. A cold request pays DNS, the TCP handshake and the TLS handshake before the request's own round trip.

**Canonical path:** [Which layer does each box read?](../02-layers.md#which-layer-does-each-box-read) → [Count the round trips](../02-layers.md#count-the-round-trips)
````

## Editing an existing question

- **The card on your review schedule is its Canonical line.** Everything else is free to reword (the heading, the prompt material, the follow-ups, Interviewer is testing, the checklist, the Worked answer and the Short answer): the card keeps its history and shows the new wording.
- A small edit to a Canonical line keeps its card. A large edit can leave the old card orphaned on the schedule beside a new question. To improve what the answer says, edit the Short answer, not the Canonical line.
- To fix a repeat, edit the later question's line, the one the warning names. It has never been a question, so it has no card. Leave the first one alone.
- If something in `interview/` is not meant to be drilled, move it out of the folder.

## Checking a change

RepoRead renders the committed `HEAD` of this repository, and the phone reads what is pushed to GitHub, so commit before checking and push before practising. From a RepoRead checkout:

```sh
REPOREAD_MEASURE_CORPUS=<path to this repository> REPOREAD_MEASURE_OUT=<private dir> ./gradlew :backend:test --tests '*RenderedCorpus' --rerun --no-daemon
REPOREAD_STUDY_PAGES=<private dir>/pages ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest --tests '*StudyCorpus' --rerun --no-daemon -i
```

`StudyCorpus` passes when every bank follows this contract. It also names Canonical lines that link nothing and links to notes that do not exist. The output contains note text, so keep `<private dir>` outside both repositories.
