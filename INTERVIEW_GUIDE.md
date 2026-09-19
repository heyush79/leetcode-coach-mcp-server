# Interview Guide: LeetCode Coach MCP Server

## 90-second explanation

> People solve hundreds of LeetCode problems and forget most of them. LeetCode's green checkmark
> records that you solved something once, not whether you could solve it today, so preparation becomes
> re-grinding a random queue while the topics you quietly lost go unnoticed.
>
> I built a Java 17 Spring Boot server that fixes that for anyone practising with an AI assistant.
> It syncs your submissions from leetcode.com and groups them into sittings. When a sitting ends — or
> you finish a session the assistant coached you through — it derives a recall grade from what it
> observed: how many attempts you needed, how many hints you asked for, whether you ran over your time
> budget. That feeds an SM-2 spaced-repetition schedule. So "what should I practise next" is answered
> with a problem you are about to forget, or one in whichever topic your review history says is
> weakest, rather than the next unsolved item in a list.
>
> It derives the grade instead of asking for one because a learner who just read a hint is a poor judge
> of whether they would have recalled the idea unaided. The session already measured that.
>
> I exposed it over MCP rather than building a web app, because the interface should be the assistant
> you already have open. Spring AI turns thirteen annotated Java methods into MCP tools over Streamable
> HTTP. Problem metadata comes from LeetCode's unofficial GraphQL endpoint behind a circuit breaker,
> with SQLite as a cache-first fallback so the tools keep working when LeetCode does not. I deliberately
> did not execute submitted code in-process, because that needs a sandboxed judge.

## Resume bullet you can defend

**LeetCode Coach MCP Server | Java 17, Spring Boot, Spring AI, SQLite, GraphQL**

- Built a Spring AI MCP server exposing 15 coding-practice tools over Streamable HTTP, with an SM-2
  spaced-repetition engine fed by an idempotent sync of the user's own submissions from LeetCode's
  authenticated GraphQL: sittings inferred from submission timing, recall graded from observed
  signals (attempts, hints, elapsed time), and schedules rebuilt by replaying history so
  out-of-order imports stay correct; hardened the unofficial integration with a Resilience4j circuit
  breaker, cache-first SQLite fallback, and WireMock contract tests, plus protocol-level MCP
  integration tests driving the live `/mcp` endpoint.

Use "Streamable HTTP with optional SSE responses" in conversation. Saying "HTTP/SSE transport" is
understandable shorthand, but the current MCP transport is Streamable HTTP; the old two-endpoint SSE
transport is deprecated.

## Component map

| Component | Responsibility |
|---|---|
| `LeetCodeCoachTools` | MCP tool contract and parameter descriptions |
| `LeetCodeProblemResource` | MCP resource at `leetcode://problem/{titleSlug}` |
| `ProblemCatalogService` | Cache-first retrieval, normalization, fallback |
| `PracticeService` | Session lifecycle, hints, attempts, streak, recommendations |
| `RecallGrader` | Turns observed session signals into a 0-5 recall grade |
| `SpacedRepetitionScheduler` | Pure SM-2 interval arithmetic |
| `ReviewService` | Applies grades to schedules; due reviews and topic mastery |
| `SubmissionSyncService` | Pages leetcode.com's submission list, imports what is new, rebuilds affected schedules |
| `SubmissionImporter` | Infers the sitting a submission belongs to and stores it, one transaction each |
| `SubmissionParser` | Interprets LeetCode's display strings: verdicts, timestamps, runtime, memory |
| `SubmissionSyncScheduler` | Runs the sync in the background once credentials are configured |
| `LeetCodeGraphQlClient` | Executes named GraphQL documents behind a circuit breaker |
| `ProblemRepository` / `PracticeRepository` / `ReviewRepository` | SQLite persistence |
| `McpAccessFilter` | Optional API key and Origin-host validation |
| `SchemaMigrations` / `SeedDataLoader` | Idempotent column additions; offline demo catalog |

## End-to-end flow

### Search flow

1. Agent calls `search_problems` through MCP.
2. Spring AI generates the MCP tool schema from `@McpTool` and `@McpToolParam`.
3. The method delegates to `ProblemCatalogService`.
4. The service calls `LeetCodeGraphQlClient` using `problemsetQuestionListV2.graphql`.
5. Each result is normalized and upserted into SQLite.
6. If GraphQL fails, the repository performs the same search against cached records.

### Practice flow

1. Agent starts a session for a title slug.
2. The problem is guaranteed to exist in the local catalog.
3. A UUID session row is inserted with `ACTIVE` status.
4. Hints are returned progressively, not all at once.
5. Each attempt is appended as an immutable row.
6. Completion updates the session and later contributes to streak and difficulty statistics.

## The spaced-repetition engine

This is the part worth spending interview time on.

### Why it exists

Solving a problem once does not mean you can solve it in four weeks. The original version of this
project stored sessions and counted them, which is a database with extra steps. Scheduling turns the
same stored data into a decision.

### Deriving the grade

SM-2 expects a recall quality from 0 to 5, normally self-reported. Self-reporting is unreliable
immediately after reading a hint, and a practice session already observed the relevant evidence:

| Signal | Effect on grade |
|---|---|
| No attempt recorded | 0 (blank) |
| No accepted attempt | 1 |
| Each hint revealed | −1, capped at −2 |
| Each failed attempt before success | −1, capped at −2 |
| Over 1.5× the session's time budget | −1 |

An accepted solution is floored at 2, not 5 — a problem that took three hints and four attempts has
not been learned, and a grade below 3 correctly resets the repetition count.

### Scheduling

Standard SM-2: intervals of 1 day, then 6 days, then `previous interval × easiness factor`. Easiness
starts at 2.5, moves by `0.1 − (5 − q)(0.08 + (5 − q)0.02)`, and is floored at 1.3. A grade below 3
is a lapse: repetitions reset to zero and the problem returns tomorrow.

Two deliberate deviations from textbook SM-2:

- **Intervals are capped at 365 days.** Uncapped SM-2 eventually schedules a review further out than
  anyone plans an interview, which is indistinguishable from dropping the problem.
- **Due dates land at the start of a UTC day**, not at the hour of the last review. Otherwise a
  problem reviewed at 21:00 would not come due until 21:00, and anyone practising earlier in the
  evening would silently miss their own reviews.

### Topic mastery

Per topic tag: average of the most recent grades, discounted by lapse rate
(`mastery = (avgGrade / 5) × (1 − min(0.5, lapses / 2n))`). Aggregated in Java rather than SQL
because topic tags live in a JSON column — SQLite would need a `json_each` join over a text field
that exists as a cache, not as a taxonomy.

### Testability

`SpacedRepetitionScheduler` and `RecallGrader` are pure and stateless, so the interval arithmetic is
unit-tested without a database or a clock. Everything time-dependent reads an injected `Clock`, which
lets the MCP integration test advance a day and assert that the review actually becomes due — the
behaviour is otherwise untestable in a single run.

## The submission sync

The original version stored what you told it. That made it a diary, and a coach who only knows what
you write in your diary is a weak coach. The sync is what turns it into one that watches.

### Where the data comes from

LeetCode's authenticated GraphQL has `submissionList`: every submission by the signed-in user,
newest first, with verdict, language, runtime, memory, and an epoch-seconds timestamp. It needs the
session cookie; an anonymous caller gets nulls rather than an error, which the client treats as a
failure with a message naming the cookie, so an expired session does not masquerade as "no
submissions".

Only the authenticated endpoint is used. The public `recentAcSubmissionList` lists accepted
submissions alone, which would make every sitting look like a first-try success and bias every grade
to 5.

### Inferring sittings

LeetCode has no session concept, only a stream. Each submission goes, in order of preference, to:

1. an assistant-coached session for the same problem that was open at the time or had ended within
   thirty minutes — the real verdict belongs next to the hints the user asked for;
2. a previously synced sitting on the same problem within two hours — the same sitting, continued;
3. otherwise a new sitting.

The gap is a heuristic and I say so. A long think between two submissions splits a sitting; that
costs a slightly harsher grade on the second half, not a wrong schedule.

### Why the schedule is rebuilt, not updated

SM-2 is a fold over graded recalls in time order. Stepping the stored state forward with each new
event is only correct when events arrive in order, and the first sync brings in months of history
older than anything recorded. So a problem's schedule is a cache: whenever its history changes, it
is recomputed by replaying every graded sitting from the start. That makes the schedule a pure
function of the history — the order the server learned it in cannot matter — and it means a
scheduling bug is fixed by replaying, not by patching rows.

### Idempotency and failure

LeetCode's submission id is unique in `attempts`, so syncing twice cannot double-count. Runs are
incremental: paging stops at the first page containing a known submission. A `full` run pages to a
cap regardless, skipping known ids, to backfill. Each submission imports in its own transaction, so
an outage part-way leaves the earlier ones stored and the next run resumes. A problem whose detail
cannot be fetched is kept as a stub so the submission is never lost. One sync runs at a time.

### What LeetCode does not expose

Hint views and source code are not in the list endpoint, and nothing says when the user started
reading the problem. Synced-only sittings therefore grade on attempts alone and start at their first
submission. Hints and time budgets count only in coached sessions, where the server observed them
directly. Saying this plainly is better than pretending the sync sees more than it does.

## Why MCP instead of REST alone?

REST exposes application endpoints, but an LLM needs additional machine-readable semantics: which operations are tools, what arguments they accept, descriptions for tool selection, structured results, resources, and protocol-level capability negotiation. MCP standardizes those concepts. I retained small REST endpoints only for health checks and manual debugging.

## Why Spring AI?

- Annotation-driven MCP tool registration.
- Automatic JSON input schema generation.
- Boot auto-configuration for Streamable HTTP transport.
- Clean integration with the rest of the Spring application.
- The same service layer remains independent of MCP and can be reused by REST or tests.

The important point is not “Spring AI calls an LLM.” In this project Spring AI primarily implements the MCP server protocol. The connected agent owns the model interaction.

## HTTP, SSE, and Streamable HTTP

The old MCP HTTP+SSE transport used one long-lived SSE endpoint plus a separate message endpoint. Streamable HTTP replaced it. A client uses HTTP POST and GET on the MCP endpoint, and the server may respond using `text/event-stream` when it needs to stream multiple messages. This project configures:

```yaml
spring.ai.mcp.server.protocol: STREAMABLE
spring.ai.mcp.server.streamable-http.mcp-endpoint: /mcp
```

## GraphQL authentication

LeetCode authentication is cookie-based in this implementation:

- `LEETCODE_SESSION` is sent in the `Cookie` header.
- `csrftoken` is also sent as a cookie.
- The same CSRF value is sent in `x-csrftoken`.
- `Origin` and `Referer` identify the LeetCode web origin.

The `verify_leetcode_auth` tool calls the `globalData` query and checks `userStatus.isSignedIn`.

### Why not store credentials in SQLite?

Credentials are supplied through environment variables and retained only in application configuration. Storing them in the database would increase persistence, backup, and accidental-commit risk. They are never returned by tools or logs.

## Why SQLite?

The project is a single-user local coaching server. SQLite gives:

- zero external infrastructure;
- durable sessions across restarts;
- transactional updates;
- a repository layer that can later be moved to PostgreSQL.

The Hikari pool is limited to one connection because SQLite has constrained concurrent writing. For multi-user deployment I would move to PostgreSQL and add user ownership to every session and attempt row.

## Schema reasoning

`problems.title_slug` is unique because it is the stable lookup key used by GraphQL and tools. The repository uses an idempotent upsert so repeat synchronization does not duplicate rows.

`practice_sessions` separates the lifecycle of a practice event from `attempts`, because one session may contain many revisions. Attempts are append-only; the final session status is mutable.

Hints, tags, and code snippets are JSON text. They are fetched and returned together and are not central relational query dimensions. Difficulty and title remain normal columns because they are frequently filtered.

## Cache strategy

- Full problem detail is cache-first.
- A caller can set `refresh=true` to bypass the cache.
- Search prefers live results and persists them.
- Search falls back to SQLite on remote failure.
- A full-problem refresh falls back only when a cached full record exists.

This is a cache-aside pattern with graceful degradation.

## Security discussion

### Outbound credentials

- Environment variables, never committed.
- No credential values in tool output or logs.
- Short-lived external cookies can expire without preventing startup.

### Inbound MCP access

- Server binds to `127.0.0.1` by default.
- Optional `MCP_API_KEY` protects `/mcp`.
- Browser Origin hosts are restricted to configured local hosts.
- Production deployment should use TLS and the MCP OAuth authorization profile rather than a shared API key.

### Code execution

The server intentionally does not compile or execute submissions. A production judge would be a separate service using short-lived containers or microVMs, read-only filesystems, disabled outbound networking, CPU/memory/time limits, and a queue. Keeping it out of this process avoids remote-code-execution risk.

## Likely interview questions

### “What exactly is an MCP tool?”

A named operation advertised by an MCP server with a description and JSON input schema. The model or agent selects it, sends structured arguments, and receives structured content. Spring AI derives the schema from Java method signatures and annotations.

### “Where is SSE used?”

The configured transport is Streamable HTTP. SSE is an optional response mode within it when the server needs to deliver multiple messages. Normal tool calls can return a single JSON response.

### “Why use GraphQL instead of scraping pages?”

GraphQL returns structured fields and lets the client request only the metadata it needs. Scraping HTML would tightly couple parsing to presentation markup. The trade-off is that this GraphQL schema is still unofficial and can change.

### “How do you handle an expired LeetCode session?”

Authentication verification returns `authenticated=false`; public queries and SQLite fallback continue to work. Startup does not depend on a successful external authentication call.

### “How is the tool idempotent?”

Read tools are naturally idempotent. Problem synchronization uses `ON CONFLICT(title_slug) DO UPDATE`. `start_practice` and `record_attempt` intentionally create new records and are not idempotent because each invocation represents a new user action.

### “Why not JPA?”

The schema is small and the SQL is useful to discuss explicitly. Spring JDBC avoids ORM configuration and SQLite dialect concerns. For a larger domain with richer relationships, JPA or jOOQ could be reasonable.

### “How would this scale?”

Move SQLite to PostgreSQL, add user IDs and authorization, run stateless MCP instances behind a load balancer, externalize rate limiting and caching, and separate code judging into queued isolated workers. The current service is deliberately optimized for a local single-user use case.

### “How do you avoid double-counting a submission?”

LeetCode's submission id is stored on the attempt with a unique index. The importer checks it before
writing, and the paging loop stops at the first page containing a known id. Both a re-run and an
overlapping page are harmless.

### “Why rebuild the schedule instead of updating it?”

Because history arrives out of order. A sync can deliver a sitting from March after a session from
today, and SM-2 stepped forward from today's state would treat March as the newest event. Replaying
from the start makes the result independent of arrival order. It also means the stored schedule is
disposable: a change to the grading rules is applied by replaying, not by migrating rows.

### “What happens when the cookie expires?”

LeetCode returns nulls, not an error. The client treats a null submission list as a failure with a
message naming the cookie; the run is recorded as failed; `get_sync_status` surfaces it; the
background job keeps trying on schedule so pasting fresh values is the whole fix. Public problem
lookups and everything already stored keep working throughout.

### “Why poll rather than push?”

LeetCode has no webhooks. Fifteen minutes is a compromise between freshness and not hammering an
unofficial endpoint; the on-demand tool covers the moment the user has just finished solving.

### “How would you stop GraphQL rate-limit issues?”

Cache problem detail, apply timeouts, limit search result size, add retry only for transient failures with jitter, and introduce a local sync schedule rather than querying on every tool call. I would not retry authentication failures.

### “What would you monitor?”

MCP request count and latency by tool, GraphQL latency/error rate, cache hit rate, SQLite write errors, active sessions, and authentication verification failures. Credentials themselves must never appear in traces.

### “How did you test it?”

Five layers, each catching what the one below cannot:

1. **Unit** — `SpacedRepetitionSchedulerTest` and `RecallGraderTest` pin the algorithm: interval
   growth, the easiness floor, lapse handling, the 365-day cap, and every grading rule.
2. **Service integration** — boots the app with remote GraphQL disabled against in-memory SQLite and
   runs a full practice workflow.
3. **Contract** — `LeetCodeGraphQlClientContractTest` runs the real client against WireMock, pinning
   the upstream response shape and asserting the circuit breaker opens and short-circuits.
4. **Sync** — `SubmissionSyncContractTest` drives the whole import against a stubbed leetcode.com:
   paging, sitting inference, grading, schedule rebuild, idempotent re-sync, attaching a submission
   to a coached session, the stub fallback, and the expired-cookie failure.
5. **Protocol** — `McpProtocolIntegrationTest` boots the server on a random port and drives `/mcp`
   over JSON-RPC, asserting all fifteen tools return spec-compliant results.

The protocol layer exists because of a real bug. Spring AI validates every tool result against a
generated output schema that marks all record components required; any null field was rejected on the
wire even though the service returned normally. Ten of eleven tools failed that way, and neither the
unit nor the service tests could see it, because both stop below the MCP boundary. The fix was to mark
optional record components `@Nullable` and serialize with `NON_NULL`; reverting it makes the protocol
test fail with the exact validation error.

## Honest project boundaries

Do not claim these features:

- reading hint usage, source code, or time-to-first-submission from LeetCode (the sync sees
  verdicts and timestamps, nothing more);
- execution of arbitrary code;
- automatic LeetCode submission;
- official LeetCode API support;
- distributed scaling with SQLite;
- OAuth 2.0 authorization when only the optional API key is configured.

A technically accurate boundary is more convincing than a fictional production load of “millions of requests.” Interviewers have heard that number from enough local TODO applications.

## Five-minute demo narration

1. Start the server and show `/api/status` reporting six cached seed problems.
2. Connect an MCP client to `/mcp` and list the thirteen tools.
3. Start `number-of-islands`, ask for two hints, record an accepted attempt.
4. Complete the session and read the returned `review` block aloud: grade 3, because two hints cost
   two points, with the rationale string explaining exactly that.
5. Call `get_topic_mastery` and show the graph topics sitting at 0.6.
6. Call `recommend_next_problem` and explain the priority order: due review, then weakest topic, then
   anything unattempted.
7. Restart the server and fetch progress statistics again to prove SQLite persistence.
8. With credentials configured, call `verify_leetcode_auth`, then `sync_leetcode_submissions` and
   watch real sittings from leetcode.com appear in `recent_practice_sessions` with
   `source: LEETCODE`, and the review schedule change without anyone reporting anything.

If asked to prove the scheduling works over time, point at `McpProtocolIntegrationTest`: it advances
the injected clock by a day and asserts the review becomes due.

## Thirty-second closing statement

> The project demonstrates that I can integrate an emerging protocol without letting protocol details
> leak into business logic, and that I can turn stored data into a decision rather than a report. The
> review engine is a real algorithm with a deliberate deviation or two that I can justify; MCP handles
> agent interoperability; the GraphQL client is isolated behind a circuit breaker because it depends on
> an unofficial API. I also designed around the two main risks: an unstable third-party interface and
> unsafe code execution.
