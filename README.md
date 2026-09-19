# LeetCode Coach MCP Server

> Unofficial educational project. It is not affiliated with or endorsed by LeetCode.

## The problem

You solve Number of Islands in March. You feel good about it. In June, in an interview, you cannot
reproduce it.

That is the normal outcome of how people practise. LeetCode shows a green checkmark forever, which
records that you solved a problem *once*, not whether you could solve it *today*. So preparation turns
into re-grinding a random queue: time spent on problems you already know cold, while the topics you
quietly lost go unnoticed until an interviewer finds them for you.

Practising with an AI assistant does not fix this on its own. The assistant has no memory between
sessions. It does not know you needed three hints on dynamic programming last week, it will hand you a
full solution the moment you sound stuck, and nothing it learns about you survives closing the tab.
You get a conversation, not a coach.

## How this solves it

This server gives an AI assistant the memory and the judgement it is missing.

1. **It watches leetcode.com.** With your session cookie configured, your submissions are pulled
   in every fifteen minutes and grouped into sittings. Solve on the site as you do now; there is
   nothing to report.
2. **It remembers.** Every sitting, attempt, hint, and verdict is persisted in SQLite and survives
   restarts, whether it came from the site or from a session coached by the assistant.
3. **It grades recall from evidence.** Each sitting produces a 0-5 recall grade derived from what
   actually happened: attempts before the first accepted one, hints revealed, time against a
   budget. Not from asking how it went, because someone who just read a hint is a poor judge of
   that.
4. **It schedules.** That grade drives an SM-2 spaced-repetition interval. A clean solve moves
   further out; a struggle comes back tomorrow.
5. **It notices weak topics.** Recall is aggregated per topic tag and discounted by how often you
   have forgotten it, so "depth-first search" can sit at 0.10 while "hash table" sits at 1.00.
6. **It plugs into the assistant you already use.** Every capability above is exposed over the
   Model Context Protocol, so Claude, Cursor, or any MCP client becomes the interface.

The result is that instead of asking for "a medium graph problem", you ask **what should I practise
today?** and get back *"Number of Islands - you failed it two days ago after three hints, and
depth-first search is your weakest topic."*

## Why an MCP server rather than a web app

A web app would be one more tab you stop opening after a fortnight. MCP puts the practice history and
the scheduling behind the assistant you already have open, so the coaching shows up inside a
conversation you were going to have anyway.

There is no user interface in this repository, and that is deliberate. The client is the AI assistant.
The REST endpoints exist only for debugging.

## Using it day to day

1. Practise on leetcode.com exactly as you do now.
2. Within fifteen minutes the server has synced the new submissions, grouped them into sittings,
   graded each one, and rescheduled those problems. To catch up immediately, tell the assistant
   *"sync my LeetCode submissions"*.
3. In Claude Code, Cursor, or any MCP client connected to the server, ask:
   - *"What should I practise today?"*
   - *"What am I weakest at?"*
   - *"How did my last few sessions go?"*

The assistant calls the tools; the tools read what actually happened.

**One-time setup for the sync:** copy `.env.example` to `.env` and fill in `LEETCODE_SESSION` and
`LEETCODE_CSRF_TOKEN` from your browser (DevTools > Application > Cookies > leetcode.com). These are
account credentials: `.env` is already git-ignored, and LeetCode will expire the session
periodically, at which point `get_sync_status` reports the failure and you paste fresh values.

## How submissions become practice history

LeetCode exposes a flat, newest-first list of your submissions (`submissionList`, authenticated).
It has no notion of a practice session, so one is inferred:

| Situation | What happens |
|---|---|
| Submission during, or shortly after, a session the assistant is coaching | Attached to that session as a real verdict, alongside the hints used |
| Submission within two hours of a previously synced sitting on the same problem | Extends that sitting |
| Anything else | Starts a new sitting for that problem |

Each sitting is graded with the same rules as a coached session. Then the problem's SM-2 schedule
is **rebuilt by replaying every graded sitting in time order**, rather than stepped forward from
its stored state. The first sync brings in months of history, much of it older than anything
already recorded; stepping forward would apply old events after new ones. Replay makes the schedule
a pure function of the history, so the order in which the server learned it cannot matter.

Runs are incremental: paging stops at the first page holding a submission already stored.
`sync_leetcode_submissions` with `full=true` pages to the configured cap regardless, skipping known
submissions, to backfill history a first bounded run did not reach. LeetCode's own submission id is
the idempotency key, so syncing twice cannot double-count.

What the sync cannot see, and how that is handled:

- **Hints viewed on leetcode.com.** Not exposed. Synced-only sittings grade on attempts alone;
  hints count only in coached sessions.
- **Time spent before the first submission.** A sitting starts at its first submission, so a
  problem solved in one go looks instantaneous. Time budgets therefore apply only to coached
  sessions.
- **Source code.** Not in the list endpoint. Synced attempts store verdict, language, runtime, and
  memory, with `code` empty.
- **A problem not yet in the local catalog.** Fetched in full so its topic tags count towards
  mastery. If LeetCode is unreachable mid-sync, a stub row keeps the submission and the detail is
  filled in on the next lookup.

LeetCode offers no webhooks, so this is polling: every fifteen minutes by default, configurable.

## Built with

Java 17, Spring Boot 4.1, Spring AI's Streamable HTTP MCP server, Spring for GraphQL against
LeetCode's unofficial endpoint behind a Resilience4j circuit breaker, and SQLite for persistence.

This repository is intentionally interview-sized: substantial enough to demonstrate protocol
integration, persistence, external API handling, and failure design, but not padded with seventeen
microservices whose main job is forwarding JSON to one another.

## What it does

- Schedules reviews with SM-2, grading recall from observed session signals rather than self-reports.
- Reports per-topic mastery so weak concepts drive the next recommendation.
- Exposes fifteen MCP tools for search, session management, hints, attempts, reviews, sync, and progress.
- Exposes a `leetcode://problem/{titleSlug}` MCP resource.
- Fetches live problem metadata through LeetCode's GraphQL endpoint, behind a Resilience4j circuit breaker.
- Syncs your own submissions from leetcode.com when `LEETCODE_SESSION` and CSRF credentials are configured, and verifies the session on request.
- Caches remote data in SQLite and falls back to six seeded problems when LeetCode is unavailable.
- Persists practice sessions, attempts, and review state across server restarts.
- Supports optional API-key protection and Origin validation on `/mcp`.
- Includes REST endpoints only for debugging and demonstrations. The actual agent integration is MCP.

## How review scheduling works

Completing a session grades recall from 0 to 5:

| Signal | Effect |
|---|---|
| No attempt recorded | grade 0 |
| No accepted attempt | grade 1 |
| Each hint revealed | −1, capped at −2 |
| Each failed attempt before success | −1, capped at −2 |
| Over 1.5x the session's time budget | −1 |

An accepted solution never grades below 2. That grade advances the problem's SM-2 state: intervals of
1 day, then 6, then `interval x easiness`, with easiness starting at 2.5 and floored at 1.3. A grade
below 3 resets the repetition count and brings the problem back tomorrow.

Two deliberate deviations from textbook SM-2: intervals are capped at 365 days, and due dates land at
the start of a UTC day so that practising earlier in the evening than the last review still surfaces
them.

`recommend_next_problem` then applies a priority order: a problem due for review, then an unattempted
problem in the weakest topic, then any unattempted problem.

## Architecture

```mermaid
flowchart LR
    A[LLM Agent / MCP Client] -->|Streamable HTTP POST/GET; optional SSE| B[Spring AI MCP Server]
    B --> C[Annotated MCP Tools]
    C --> D[Problem Catalog Service]
    C --> E[Practice Service]
    E --> J[Review Service]
    J --> K[Recall Grader + SM-2 Scheduler]
    D --> F[Spring GraphQL HTTP Client]
    F --> L[Circuit Breaker]
    L --> G[LeetCode GraphQL]
    D --> H[(SQLite)]
    E --> H
    J --> H
    I[Optional API key + Origin filter] --> B
```

### Request flow: `get_problem`

1. The MCP client calls `get_problem` with a title slug.
2. Spring AI validates and binds the JSON tool arguments to the annotated Java method.
3. `ProblemCatalogService` checks SQLite first unless refresh is requested.
4. On a cache miss, `LeetCodeGraphQlClient` executes `questionData.graphql`.
5. The result is normalized and upserted into SQLite.
6. If the remote call fails but cached data exists, the cached problem is returned.

## Tech stack

- Java 17
- Spring Boot 4.1.0
- Spring AI 2.0.0 MCP WebMVC starter
- Spring for GraphQL `HttpSyncGraphQlClient`
- Spring JDBC
- SQLite via Xerial JDBC
- Resilience4j circuit breaker
- JUnit 5, AssertJ, Mockito, and WireMock

## Run locally

### Prerequisites

- JDK 17
- Maven 3.6.3+

```bash
git clone <your-repository-url>
cd leetcode-coach-mcp-server
cp .env.example .env
set -a && source .env && set +a
mkdir -p data
mvn clean verify
LEETCODE_REMOTE_ENABLED=false mvn spring-boot:run
```

The server binds to `127.0.0.1:8080` by default.

```bash
curl http://127.0.0.1:8080/actuator/health
curl http://127.0.0.1:8080/api/status
curl "http://127.0.0.1:8080/api/problems?difficulty=MEDIUM&limit=5"
```

The MCP endpoint is:

```text
http://127.0.0.1:8080/mcp
```

Use an MCP client or the MCP Inspector to connect through Streamable HTTP. A generic client configuration is available in `mcp-client.example.json`.

## Run with Docker

```bash
cp .env.example .env
docker compose up --build
```

SQLite is stored in a named Docker volume.

## LeetCode authentication

Public problem lookups work without an account. Syncing *your* submissions requires your session,
because LeetCode only lists them to the signed-in user:

```bash
export LEETCODE_SESSION='your-session-cookie'
export LEETCODE_CSRF_TOKEN='your-csrf-cookie'
mvn spring-boot:run
```

You can obtain the cookie values from your own browser session under Developer Tools, Application/Storage, Cookies, `leetcode.com`. Treat both values as credentials.

Then call `verify_leetcode_auth` to confirm the session is accepted, and `sync_leetcode_submissions`
to import your history. After that the background sync keeps up on its own.

### Important limitation

LeetCode's GraphQL interface is not presented as a stable public developer API. Query fields can change, authentication cookies expire, and automated access may be rate-limited. The project isolates that risk inside `LeetCodeGraphQlClient` and uses SQLite fallback rather than pretending third-party systems possess eternal schemas.

## Optional MCP API key

For localhost development, `MCP_API_KEY` is blank. To require a key on `/mcp`:

```bash
export MCP_API_KEY='replace-with-a-long-random-value'
mvn spring-boot:run
```

Clients can send either:

```text
X-API-Key: replace-with-a-long-random-value
```

or:

```text
Authorization: Bearer replace-with-a-long-random-value
```

The filter also rejects browser requests with an Origin host outside `localhost` and `127.0.0.1` by default. Requests without an Origin header, such as normal server-to-server MCP clients, are allowed.

## MCP tools

| Tool | Purpose |
|---|---|
| `search_problems` | Search live GraphQL data, then fall back to SQLite |
| `get_problem` | Load full problem detail and cache it |
| `start_practice` | Create a persisted coaching session |
| `get_session_context` | Load the problem and all previous attempts |
| `get_hint` | Return progressive hints at levels 1 to 3 |
| `record_attempt` | Store code, verdict, complexity, and notes |
| `complete_practice` | Complete a session, grade recall, and schedule the next review |
| `get_progress_stats` | Return totals, active days, streak, reviews due, and difficulty split |
| `get_due_reviews` | List problems whose review is due, most overdue first |
| `get_topic_mastery` | Report recall performance per topic, weakest first |
| `recommend_next_problem` | Due review, then weakest topic, then any unattempted problem |
| `verify_leetcode_auth` | Check the configured LeetCode session |
| `sync_leetcode_submissions` | Pull recent submissions from leetcode.com now; `full=true` backfills older history |
| `get_sync_status` | Whether sync is configured, when it last ran, and how many submissions are in |
| `recent_practice_sessions` | List recent sessions |

## Suggested demo

For a deterministic demo, start with `LEETCODE_REMOTE_ENABLED=false`; enable the remote client only for the separate GraphQL/authentication segment.

1. Call `search_problems` with `difficulty=MEDIUM` and `topic=graph`.
2. Call `start_practice` for `number-of-islands`.
3. Call `get_hint` at level 2.
4. Call `record_attempt` with a short BFS or DFS implementation and `verdict=ACCEPTED`.
5. Call `complete_practice`. The response carries a `review` block: grade 3, because two hints cost two points, with a rationale saying so.
6. Call `get_topic_mastery` and see the graph topics at 0.6.
7. Call `recommend_next_problem` and read the reason it gives.
8. Restart the application and call `get_progress_stats` again to demonstrate persistence.

## Database schema

### `problems`

Caches normalized remote or seed problem metadata. JSON arrays such as hints, tags, and code snippets are serialized into text columns because SQLite is being used as an embedded cache, not as a platform for analytical joins across topic taxonomies.

### `practice_sessions`

Tracks one coaching session per problem attempt with active/completed state, timestamps, target duration, and notes.

### `attempts`

Stores submitted source code, verdict, optional runtime/memory, complexity claims, and reflection notes.

### `review_schedule`

One row per practised problem holding SM-2 state: easiness factor, current interval, consecutive
repetitions, lapse count, last grade, and the next due date. A cache: rebuilt from session history
whenever that history changes.

### `sync_runs`

One row per sync against leetcode.com: submissions seen and imported, sessions created or extended,
problems added, and the failure message if any.

`practice_sessions.source` and `attempts.source` record whether a row came from the MCP tools
(`MANUAL`) or the sync (`LEETCODE`). `attempts.leetcode_submission_id` is unique, which is what
makes the sync idempotent.

## Failure handling

- Live search failure: log the upstream error and query SQLite.
- Problem refresh failure: return the cached full problem when available.
- Expired credentials: return an unauthenticated status instead of crashing startup.
- Duplicate problem sync: SQLite `ON CONFLICT(title_slug) DO UPDATE` performs an idempotent upsert.
- Concurrent SQLite writes: Hikari pool size is one because this is an embedded single-node service.
- Invalid session mutation: reject attempts after a session is completed.
- Nullable tool results: records expose optional fields as `@Nullable` and serialize with `NON_NULL`, because Spring AI's generated output schema marks every component required and rejects a null on the wire.
- Repeated upstream failures: a Resilience4j circuit breaker opens after a configurable failure rate and fails fast, so tools fall back to SQLite immediately instead of waiting out the timeout on every call.
- Repeated completion: completing an already-completed session returns stored state without re-grading, so it cannot inflate the review schedule.
- Schema drift: `SchemaMigrations` adds columns that `CREATE TABLE IF NOT EXISTS` would skip on an existing database, preserving practice history.
- Expired LeetCode cookie: LeetCode answers with nulls rather than an error. The sync records a failed run whose message names the cookie, and the background job keeps trying on schedule.
- LeetCode unreachable mid-sync: each submission is imported in its own transaction, so what was stored stays stored and the next run resumes. A problem whose detail could not be fetched is kept as a stub.
- Overlapping syncs: one runs at a time; a second request is reported as skipped rather than interleaving page reads.

## What the server deliberately does not do

It does not execute arbitrary submitted code. Secure code execution requires process isolation, CPU and memory quotas, filesystem restrictions, network controls, and language-specific runners. The MCP server records attempts and lets the connected LLM reason about them. A production extension would send code to an isolated judge service instead of invoking a compiler inside the Spring process like a person requesting an incident report in advance.

It also does not submit solutions to LeetCode. That would couple the project to additional private mutations and create avoidable account risk.

## Repository safety

Safe to commit:

- Java source
- GraphQL query documents
- `schema.sql`
- `.env.example`
- Docker and CI configuration

Never commit:

- `.env`
- `LEETCODE_SESSION`
- `LEETCODE_CSRF_TOKEN`
- real MCP API keys
- generated SQLite database files
- IDE files and build output

The included `.gitignore` covers these files.

## Test

```bash
mvn clean verify
```

The suite has three layers:

- `PracticeServiceTest` covers session creation, progressive hints, and streak calculation with mocked collaborators.
- `ApplicationIntegrationTest` runs an offline workflow through the service layer against in-memory SQLite.
- `SpacedRepetitionSchedulerTest` and `RecallGraderTest` pin the algorithm: interval growth, easiness floor, lapse handling, the interval cap, and every grading rule.
- `LeetCodeGraphQlClientContractTest` runs the real GraphQL client against WireMock, pinning the upstream response shape and asserting the circuit breaker opens and short-circuits.
- `SubmissionSyncContractTest` runs the sync against a stubbed leetcode.com: paging, session inference, grading, schedule rebuild, idempotent re-sync, attaching a submission to a coached session, a stub for unreachable problem detail, and the expired-cookie failure.
- `McpProtocolIntegrationTest` boots the server on a random port and drives the real `/mcp` endpoint over JSON-RPC, asserting that all fifteen tools return spec-compliant results, and advancing an injected clock to prove a review becomes due.

The third layer exists because the first two cannot see the MCP boundary. Spring AI validates every tool result against a generated output schema, so a tool can succeed in Java and still be rejected on the wire. Only a test that speaks the protocol catches that.

Time-dependent behaviour reads an injected `Clock`, so review scheduling is tested by advancing time rather than waiting for it.

## Main design trade-offs

| Decision | Benefit | Cost |
|---|---|---|
| Synchronous WebMVC MCP server | Simple debugging and JDBC integration | A blocked upstream call occupies a request thread |
| SQLite | Zero-setup persistence and easy demo | Single-node; limited concurrent writers |
| GraphQL query documents | Typed mapping and readable operations | Coupled to an unofficial external schema |
| Cache-first problem detail | Fast and resilient | Data can become stale until refresh |
| No in-process code execution | Safe and small scope | Verdict is supplied externally or by the user |
| Optional API key | Easy local setup with a security path | Not a full OAuth 2.0 MCP authorization implementation |
| Derived recall grade | Honest signal; no self-rating bias after a hint | Heuristic weights, not calibrated against outcome data |
| SM-2 rather than FSRS | Small, explainable, no training data needed | Less accurate than modern schedulers |
| Circuit breaker on GraphQL | Fast degradation during an outage | One more state to reason about when debugging |
| Sittings inferred from submission timing | Works with the only data LeetCode exposes | A two-hour gap is a heuristic; a long think between submissions splits a sitting |
| Schedule rebuilt by replay | Correct for out-of-order history; a scheduling fix applies by replaying | Recomputes a problem's history on every change |
| Polling every 15 minutes | LeetCode has no webhooks | Up to 15 minutes stale unless synced on demand |
| Session cookie as credential | The only way in; the site itself uses it | Expires and must be refreshed by hand |

See `INTERVIEW_GUIDE.md` for the explanation you should give rather than improvising architecture mythology under fluorescent lighting. See `VALIDATION.md` for build and smoke-test commands.
