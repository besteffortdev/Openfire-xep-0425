# Java code standards for this project

This is the standard the sister Openfire-federation and Openfire-SAMessage plugins are reviewed
against, re-derived against *this* codebase. It answers two questions:

- **Part 1: what goes wrong.** The ranked list of the most-cited complaints about AI-generated Java
  (2022–2026). It tells you what to look for in a review.
- **Part 2: what good looks like.** The widely agreed Java canon (Oracle, Google, *Effective Java*,
  SEI CERT, OWASP, SonarSource), plus the professional-ethics codes that have concrete consequences
  in code. It tells you what to write in the first place.

Each Part 2 item carries this repo's status: **PASS** (checked, don't re-fix), **GAP** (real and
actionable), **JUSTIFIED** (a deliberate deviation, with the reason recorded) or **N/A**.

**What sets this plugin apart from its sisters**, and it affects every row below, is where the trust
boundary sits:
- **Who is authenticated.** The requester's address is: Openfire stamps `from` from the
  authenticated session, local or S2S.
- **What is not trusted.** Everything else in a moderation request is whatever the client sent. The
  stored stanzas the plugin reads back hold other occupants' message text.
- **Where authorization happens.** It is not at the parser. It is the requester's *current role*
  in the room, looked up by the worker.

The plugin also rewrites shared state that other components own: core's archive table, core's
join-history cache, and the Monitoring plugin's MAM results. So "failure leaves things consistent"
counts for more here than in a plugin that keeps state of its own.

---

## Part 1: The AI-code complaint ranking

Ordered by evidence weight, not by how annoying they feel.

| # | Complaint | Evidence |
|---|---|---|
| 1 | **"Almost right, but not quite"**: plausible logic with a subtly wrong branch | 66% of developers, Stack Overflow Developer Survey 2025 |
| 2 | **Missing or swallowed error handling** | ~2× the human rate (CodeRabbit) |
| 3 | **Security defects** | Veracode: insecure in 45% of tasks; **Java worst at ~72%** |
| 4 | **Duplication instead of reusing an existing helper** | GitClear: duplicated blocks up 8× in 2024 |
| 5 | **God methods / god classes** | Cognitive-complexity violations dominate Sonar scans |
| 6 | Null handling and NPE risk | |
| 7 | Comment noise: restating *what*, never *why* | |
| 8 | Hallucinated APIs and methods | |
| 9 | Missing input validation at trust boundaries | |
| 10 | Style drift within one codebase | |
| 11 | Over-engineering / speculative generality | |
| 12 | Tautological tests that assert the implementation | |
| 13 | Concurrency misuse | |
| 14 | Performance anti-patterns | |
| 15 | Magic numbers | |

**Complaint #1 found both real defects in 0.1.2, and it found them in comments.** In each case a
comment made a promise the code beside it did not keep:

- `ModerationInterceptor` said a failure "leaves the request unconsumed so core still answers it".
  But it consumed the request *before* handing it on, so a failure there left the moderator with no
  answer at all.
- `destroyPlugin` said its steps were guarded so that "one failing must not leave the next undone,
  least of all the interceptor removal". But its first step could throw an `Error` past its guard,
  and the interceptor came *after* it.

When a comment explains *why*, as this codebase's comments do, it is also a specification. Reading
each one against the code it sits on is the cheapest review there is.

---

## Part 2: The agreed canon

The sources, and what each is good for, are as in the sister projects:
- [Oracle conventions](https://www.oracle.com/java/technologies/javase/codeconventions-namingconventions.html): naming.
- [Google Java Style](https://google.github.io/styleguide/javaguide.html): mechanical rules.
- *Effective Java*, 3rd ed.: design.
- [SEI CERT](https://cmu-sei.github.io/secure-coding-standards/sei-cert-oracle-coding-standard-for-java/): security and robustness.
- [OWASP](https://owasp.org/www-project-secure-coding-practices-quick-reference-guide/): boundary checklist.
- [SonarSource](https://www.sonarsource.com/blog/top-issues-in-java-projects/): which violations correlate with faults.
- The [ACM](https://www.acm.org/code-of-ethics) and [ACM/IEEE-CS](https://www.computer.org/education/code-of-ethics) codes: obligations.

Openfire's only formally enforced convention is its `.editorconfig`, which this repo now carries
too. For Java style proper, Ignite Realtime points contributors at
[Smack's guidelines](https://github.com/igniterealtime/Smack/wiki/Guidelines-for-Smack-Developers-and-Contributors).

DRY, KISS, YAGNI and SOLID are **tensions to manage, not rules to pass**. Treat a "violation" as a
prompt to justify the trade-off.

### A. Exceptions and failure
*CERT ERR-\*, TPS03-J, Effective Java ch. 10, Sonar S112/S1166*

| Rule | Status here |
|---|---|
| **ERR03-J** Restore prior object state on failure (EJ Item 76) | **FIXED in 0.1.2.** See "A start that fails part-way" below. |
| **ERR07-J** Do not throw `Exception`/`Throwable` (Sonar S112) | **PASS.** There is no `throws Exception` anywhere. Checked failures are `SQLException`, `InterruptedException` and the plugin's own `ModerationException`, which carries the XMPP error condition to answer with. |
| **ERR07-J (catch side)** Do not catch `Exception` where a narrower type exists | **PASS / JUSTIFIED.** Every broad catch is classified below. None can be narrowed. |
| **TPS03-J** Tasks in a thread pool must not fail silently | **PASS.** Both of the plugin's threads have a barrier: `ModerationHandler.run`, and `ModerationReplay.replay`. Each logs its failure, and the handler's barrier also guarantees the moderator exactly one answer. |
| **ERR00-J** Never suppress a checked exception | **PASS.** `ConversationLog.parse` turns a parse failure into `Optional.empty()`. That is a documented conversion: an old row from before Openfire stored stanzas can carry no stanza-id, so it can match nothing. It is not a silent drop. |
| **ERR01-J / IDS15-J** Do not expose sensitive information across a trust boundary | **PASS.** The requester gets the bare RFC 6120 condition and nothing else. `ModerationException`'s message is for the log only, and its javadoc says so. |
| **ERR04-J / ERR05-J** Never complete abruptly from `finally` | **PASS** |
| **EJ Item 69** Use exceptions only for exceptional conditions | **PASS.** "Not a moderation request", the common case on every IQ, is `Optional.empty()`, not an exception. |

#### The broad catches, classified

| Where | Verdict |
|---|---|
| `ModerationHandler.run` | **Must stay broad.** It is the worker's barrier: TPS03-J, plus the one-answer guarantee. |
| `ModerationReplay.replay` | **Must stay broad.** It is the scheduled task's barrier (TPS03-J). |
| `ModerationHandler.backfillMonitoring`, the task | **Must stay broad.** It is the start-up backfill's barrier on the worker (TPS03-J). It logs, and the backfill is retried at the next start. Added in 0.1.3. |
| `ModerationInterceptor.interceptPacket` | **Must stay broad.** An exception escaping it reaches core's interceptor loop, which runs for every packet on the server. |
| `ModerationPlugin.destroyPlugin`, one per step (4) | **Must stay broad.** These are isolation barriers: one failed step must not abandon the rest. |
| `ModerationPlugin.onEnabledChange` | **Must stay broad.** It runs on Openfire's thread when an administrator changes the switch. |
| `ModerationPlugin.initializePlugin`, `catch (Throwable)` | **Deliberate.** It rolls back and rethrows the same object. Nothing is swallowed, and any rollback failure is attached with `addSuppressed`. `Error` is included because a `NoClassDefFoundError` from an incompatible Openfire is the likeliest way a start fails. |
| `destroyPlugin`'s `Exception \| LinkageError` on the property steps | **Deliberate, and narrow.** If `ModerationProperties` never initialized, there is nothing of it to undo, and the error must not end the cleanup. |

#### A start that fails part-way

Openfire does **not** call `destroyPlugin()` for a plugin whose `initializePlugin()` threw. This was
confirmed by reading `PluginManager.loadPlugin` in 5.1.0: it logs, counts the failure and returns.

Before 0.1.2, a failure after the interceptor was registered left it registered:
- It went on consuming every moderation request on the server.
- It handed them to a handler that nothing would ever shut down.
- No administrator could unload it, because the plugin had never finished loading. Only a restart
  cleared it.
- Each automatic retry of the load added another.

`initializePlugin` now rolls back through `destroyPlugin` before rethrowing.

**Writing the test for that fix found a second bug.** Without a server, the start fails with an
`ExceptionInInitializerError` from `ModerationProperties`. The rollback then hit the *same* class
in its first step, which caught only `Exception`, so the `Error` ended the cleanup before the
interceptor was removed. `destroyPlugin` now removes the interceptor first, since it is the one
component that does harm if left behind. The property steps come last and tolerate a
`LinkageError`.

`ModerationPluginTest` pins both. It was run against the 0.1.1 class and fails there, so the test
reproduces the bug rather than restating the fix.

### B. Security and trust boundaries
*CERT IDS/FIO/MSC, OWASP*

| Rule | Status here |
|---|---|
| **IDS00-J** Prevent SQL injection | **PASS.** Every query is a `PreparedStatement` with bound parameters. The one `LIKE` pattern goes through `likeEscape` with an explicit `ESCAPE` character, and its input is a stanza-id that has already been validated. |
| **IDS17-J** Prevent XML external entity attacks | **PASS.** `ConversationLog.hardenedReader` refuses DOCTYPE outright. The column was written by Openfire, but it holds any occupant's message text. `ConversationLogTest.refusesDoctype` covers it. Verified; do not "harden" it again. |
| **IDS16-J** Prevent XML injection | **PASS.** Every stanza is built through dom4j `Element`/`addAttribute`, never by concatenating strings. |
| **IDS03-J** Do not log unsanitized user input | **PASS.** The only client-chosen value that reaches the log is a rejected stanza-id, and it goes through `LogSafe.text()`. Accepted ids match `[A-Za-z0-9._~:@+=/-]`, so they carry no control characters. Nicknames and JIDs were parsed by Openfire, and RFC 7622 forbids control characters in them. `LogSafeTest` was ported from SAMessage in 0.1.2, plus a case that drives a forged newline through `ModerationRequest.parse`. |
| **Input validation at the boundary** (OWASP) | **PASS.** `ModerationRequest.parse` is the single entry point. The stanza-id is checked against an allowlist pattern and capped at 128 characters. The reason is trimmed and capped at 1000. |
| **Authorization** | **PASS.** The requester's *current role* is checked on every request, not their affiliation, so an owner demoted for the session cannot moderate. It is checked in the worker, against the live room, not in the parser. |
| **Forged identifiers** | **PASS.** Only a stanza-id whose `by` is the room counts (`Retraction.carries`), so a client-supplied stanza-id cannot make its message match someone else's. A request from one room cannot match a message in another. Both are pinned by `RetractionTest.carriesOnlyRoomStanzaId`. |
| **Resource exhaustion** | **PASS.** The request queue is bounded at 64 and refuses with `resource-constraint` when full. The replay queue sheds joins past 1000. A replay is capped at 100 stanzas, and the list is cached per room. |
| **MSC02-J** Generate strong random numbers | **PASS.** The only randomness is `UUID.randomUUID()` for stanza ids, and it is backed by `SecureRandom`. |
| **MSC03-J** Never hard-code sensitive information | **PASS** in the plugin. See section F for the repo's tooling files. |

### C. Concurrency
*CERT VNA/LCK/TPS, Effective Java ch. 11*

| Rule | Status here |
|---|---|
| **EJ Item 80** Prefer executors to threads | **PASS.** Two executors, no `Thread` subclass. The thread factories only name the threads and mark them as daemons. |
| **Serialization of moderations** | **PASS, by design.** There is one worker thread, so two moderations of the same room never interleave. |
| **Shared cache writes** | **PASS.** `RoomHistory.replace` does its read-modify-write under core's own `Cache.getLock` for the room, exactly as `HistoryStrategy.addMessage` does, so it is cluster-safe. |
| **VNA02-J** Compound operations must be atomic | **JUSTIFIED** in two places, both documented where they occur. `ModerationReplay`'s cache is get-then-put, so a replay racing a moderation can cache the old list for at most 60 s, and only for joins whose clients were present for the live announcement. Its `MAX_PENDING` check is a soft cap that can overshoot by the number of concurrent joins. Neither is worth a lock. |
| **Bounded shutdown** | **FIXED in 0.1.2** (style drift, #10). The handler waited up to 5 s for its task in progress, but the replay did not wait at all, so a replay could still be routing packets after Openfire had unloaded the plugin's classes. Both now stop through one helper, `ModerationHandler.stop`, with one named `SHUTDOWN_WAIT`. |
| **LCK00-J** Private final lock objects | **N/A.** There are no `synchronized` blocks. The only lock is core's. |
| **THI03-J** `wait()` in a loop | **N/A** |

### D. API and object design
*Effective Java ch. 2–6, CERT OBJ/MET*

| Rule | Status here |
|---|---|
| **OBJ01-J / EJ Item 15** Minimize accessibility | **PASS.** Only `ModerationPlugin` is public, because Openfire instantiates it. Every other class is package-private and `final`. Tests reach them without widening the API. |
| **EJ Item 17** Minimize mutability | **PASS.** Value types are `record`s: `ModerationRequest`, `ModeratedBy`, `Entry`, `Cached`. The cached replay list is `List.copyOf`. |
| **EJ Item 62** Avoid strings where other types are better | **PASS.** Error conditions are `PacketError.Condition` and roles are `Role`, both enums. The handler compares `getRole() != Role.moderator`, not a string. |
| **EJ Item 54 / 55** Return empty, not null; `Optional` for "maybe" | **PASS.** Every lookup returns `Optional` or an empty list. |
| **MET09-J** `equals()` implies `hashCode()` | **PASS.** There is no hand-written `equals`; records generate both. |
| **Serializable exception state** (Sonar S1948) | **FIXED in 0.1.2.** `ModerationException.condition` was `transient`, so a deserialized exception would have lost the one thing it exists to carry. It is an enum, so it is serializable as it is. |
| **Text truncation** | **FIXED in 0.1.2.** The reason cap could cut a surrogate pair in half. A lone surrogate is not a character XML can carry, and the reason is both broadcast to every occupant and stored in the tombstone. The cap now backs off one char rather than split a pair. Covered by `capsReasonOnACharacterBoundary`. `LogSafe`'s own cap was left exactly as in the sister repos: its output only goes to the log, where a lone surrogate degrades to `?`, which is its substitution character anyway. |

### E. Readability and maintainability
*Sonar, Google, Oracle, Smack*

| Rule | Status here |
|---|---|
| **S1135** No `TODO`/`FIXME` · **S125** no commented-out code | **PASS.** Zero of each. |
| **S1128** No unused imports · wildcard imports · **S3740** raw types | **PASS.** Zero of each. |
| **Parameterized logging** | **PASS.** Zero concatenated `Log.*` calls. |
| **S1192** String literals should not be duplicated | **PASS.** Every element name is a `QName` constant, and 0.1.2 hoisted the last two inline ones in `Retraction`. Two collisions are deliberately *not* merged. `reason` names two different elements: `ModerationRequest.REASON` is in the moderation namespace, while `Retraction.RETRACT_REASON` is in the retraction namespace. `stamp` is an attribute of both `<retracted/>` and XEP-0203 `<delay/>`. Hoisting either into one constant would couple vocabularies that are free to diverge. |
| **Duplication** (#4) | **FIXED in 0.1.2** where it mattered. The live announcement and its replay built the same envelope in two places: `groupchat`, from the bare room, a fresh id, and `<retract id='…'/>`. That envelope is exactly what clients check before they act, so a change to one copy alone would have made replays silently ignored. It now has one home, `Retraction.retractionFrom` / `addRetract`. **Deliberately left alone:** the room lookup in the handler and in the replay is two lines with different null handling, so a shared helper would cost more than it saves. |
| **Magic numbers** (#15) | **PASS.** Every limit is a named constant with a javadoc saying *why* that value: `MAX_ARCHIVE_WAIT`, `QUEUE_CAPACITY`, `SHUTDOWN_WAIT`, `JOIN_DELAY`, `CACHE_TTL`, `MAX_REPLAYED`, `MAX_PENDING`, `MAX_REASON_CHARS`, `MAX_CHARS`. |
| **Cognitive complexity** | **PASS.** The longest method is `ModerationHandler.moderate`, at about 65 lines, and it reads top to bottom in the order its class javadoc documents. |
| **`.editorconfig`** (4-space, LF, UTF-8, final newline, max 120) | **PASS.** The file was added in 0.1.2, and the tree already conformed. |
| **Braces on single-statement blocks** (Google, Smack) | **JUSTIFIED: house style.** About 20 one-line guard clauses (`if (x) return;`) have no braces. That is the convention across all three Ignite-style plugins here (529 in federation, 249 in SAMessage). Complaint #10 is drift *within* a codebase, so matching the siblings is the compliant choice. Bracing only this repo would introduce the drift. |
| **Javadoc `@param`/`@return`/`@throws`** | **Mostly PASS.** Class javadoc explains *why* in every class, and the cross-checked comments are what found the 0.1.2 defects. 0.1.2 added the missing `@throws SQLException` tags and fixed an inline `@return` that javadoc would have rendered as plain text. Private helpers carry a one-line summary only, deliberately. |

### F. Professional obligations that show up in the code
*ACM Code of Ethics; ACM/IEEE-CS Software Engineering Code of Ethics*

| Principle | Code consequence | Status here |
|---|---|---|
| **ACM 1.2: Avoid harm** | Fail safe. | **PASS.** Every step that can fail comes *before* the announcement. A failure leaves clients still showing the message, visibly wrong and retryable, rather than deleting a message the server still serves. Also, a failed start no longer leaves a live interceptor (section A). |
| **ACM 1.3: Be honest** | The server must not claim what it did not do. | **PASS.** The moderator gets a result only after the archive and history were rewritten. The log line states which of the two actually happened. |
| **ACM 1.6: Respect privacy** | Do not log message content. | **PASS.** The log carries who, where and which id, never the moderated text. A comment on the log line says so. A tombstone keeps an allowlist of envelope fields, so an attachment URL, an HTML body or a client extension added next year cannot survive moderation by default. |
| **ACM 1.7: Honor confidentiality** | Internal infrastructure must not ship. | **PASS** for the plugin jar: `readme.html` and `plugin.xml` contain no hosts. **Watch:** `deploy.sh` and `tools/live_check.py` name the test server's address and hostname, and they are in the public GitHub mirror's history. They are not secrets, but the sister projects publish to GitHub through a sanitized mirror for exactly this reason. |
| **ACM 2.5 / SE 3.10: Adequate testing** | Tests in proportion to risk. | **PASS.** 41 tests, run offline with `mvn -o test`, cover the wire contract, the trust boundary, log forging, and the plugin lifecycle. What is *not* covered by unit tests is anything needing a live room: authorization against a real role, the archive wait, and the join replay. `tools/live_check.py` exists for those. |
| **SE 3.11: Document known limitations** | Say what does not work. | **PASS.** See README § Status, which includes the one internal-API dependency (`RoomHistory`) and how it degrades. |
| **SE 6.07 / 8.02: Be accurate** | Say what was verified and what was not. | **PASS.** The README separates "verified with Conversations" from "deployed, not yet confirmed". |

---

## Ranked action list

**Done in 0.1.2:**

1. ~~Make a failed start roll back~~ (ERR03-J). It was a live defect: an orphaned interceptor that
   survived until restart. It came with a second defect in the rollback path, which the test found.
2. ~~Hand the request on before consuming it~~ (#1). This makes the interceptor's fallback comment
   true.
3. ~~Give both threads the same bounded shutdown~~ (#10).
4. ~~Cap the reason on a character boundary~~.
5. ~~Keep `ModerationException.condition` serializable~~ (S1948).
6. ~~One home for the retraction envelope~~ (#4). Hoisted the last inline `QName`s (S1192).
7. ~~Guard the property listener~~. It runs on Openfire's thread.
8. ~~Add `LogSafeTest` and `ModerationPluginTest`; `.editorconfig`; `@throws` tags~~.

**Remaining:**

9. **Run `tools/live_check.py`** (SE 3.10). It covers the paths unit tests cannot reach, and it has
   never been run.
10. **Decide about the tooling files on the public mirror** (ACM 1.7). Either accept the address and
    hostname in history, or move to a sanitized mirror like the sister projects. That means
    rewriting the GitHub history once.

## How 0.1.2 was verified

- `mvn -o test`: 38 tests, all passing, offline, with no server.
- `ModerationPluginTest` was run against the 0.1.1 `ModerationPlugin` and **fails there**
  ("the interceptor outlived a failed start"). So it reproduces the defect rather than describing
  the fix.
- The `Retraction` refactor changes no wire format. `RetractionTest`'s 14 cases pin the
  announcement, the tombstone and the replay element by element, and they pass unchanged.
- The Openfire behaviour this relies on was read from the 5.1.0 bytecode, not assumed:
  - `PluginManager.loadPlugin` does not call `destroyPlugin` after a failed init.
  - `MUCEventDispatcher` catches per listener.
