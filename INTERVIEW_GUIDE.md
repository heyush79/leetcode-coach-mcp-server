# Interview Guide: LeetCode Coach MCP Server

## 90-second explanation

> I built a Java 17 Spring Boot MCP server that turns LeetCode practice into a scheduled curriculum
> rather than a list of problems you grind and forget.
>
> The interesting part is the review engine. When you finish a practice session, the server derives a
> recall grade from what it observed — how many attempts you needed, how many hints you asked for,
> whether you ran over your time budget — and feeds that into an SM-2 spaced-repetition schedule. So
> `recommend_next_problem` does not just hand you an unsolved problem; it prefers one you are about to
> forget, and falls back to a new problem in whichever topic your review history says is weakest.
>
> The reason it derives the grade instead of asking for one is that a learner who just read a hint is a
> poor judge of whether they would have recalled the idea unaided. The session already measured that.
>
> Around that, Spring AI exposes thirteen annotated Java methods as MCP tools over Streamable HTTP.
> Problem metadata comes from LeetCode's unofficial GraphQL endpoint through Spring for GraphQL, behind
> a circuit breaker, with SQLite as a cache-first fallback so the tools keep working when LeetCode does
> not. I deliberately did not execute submitted code in-process, because that needs a sandboxed judge.

## Resume bullet you can defend

**LeetCode Coach MCP Server | Java 17, Spring Boot, Spring AI, SQLite, GraphQL**

- Built a Spring AI MCP server exposing 13 coding-practice tools over Streamable HTTP, with an SM-2
  spaced-repetition engine that grades recall from observed session signals (attempts, hints, elapsed
  time) to schedule reviews and surface weak topics; hardened the unofficial LeetCode GraphQL
  integration with a Resilience4j circuit breaker, cache-first SQLite fallback, and WireMock contract
  tests, plus protocol-level MCP integration tests driving the live `/mcp` endpoint.

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

### “How would you stop GraphQL rate-limit issues?”

Cache problem detail, apply timeouts, limit search result size, add retry only for transient failures with jitter, and introduce a local sync schedule rather than querying on every tool call. I would not retry authentication failures.

### “What would you monitor?”

MCP request count and latency by tool, GraphQL latency/error rate, cache hit rate, SQLite write errors, active sessions, and authentication verification failures. Credentials themselves must never appear in traces.

### “How did you test it?”

Four layers, each catching what the one below cannot:

1. **Unit** — `SpacedRepetitionSchedulerTest` and `RecallGraderTest` pin the algorithm: interval
   growth, the easiness floor, lapse handling, the 365-day cap, and every grading rule.
2. **Service integration** — boots the app with remote GraphQL disabled against in-memory SQLite and
   runs a full practice workflow.
3. **Contract** — `LeetCodeGraphQlClientContractTest` runs the real client against WireMock, pinning
   the upstream response shape and asserting the circuit breaker opens and short-circuits.
4. **Protocol** — `McpProtocolIntegrationTest` boots the server on a random port and drives `/mcp`
   over JSON-RPC, asserting all thirteen tools return without a protocol error.

The fourth layer exists because of a real bug. Spring AI validates every tool result against a
generated output schema that marks all record components required; any null field was rejected on the
wire even though the service returned normally. Ten of eleven tools failed that way, and neither the
unit nor the service tests could see it, because both stop below the MCP boundary. The fix was to mark
optional record components `@Nullable` and serialize with `NON_NULL`; reverting it makes the protocol
test fail with the exact validation error.

## Honest project boundaries

Do not claim these features:

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
8. With credentials configured, call `verify_leetcode_auth` and refresh a live problem.

If asked to prove the scheduling works over time, point at `McpProtocolIntegrationTest`: it advances
the injected clock by a day and asserts the review becomes due.

## Thirty-second closing statement

> The project demonstrates that I can integrate an emerging protocol without letting protocol details
> leak into business logic, and that I can turn stored data into a decision rather than a report. The
> review engine is a real algorithm with a deliberate deviation or two that I can justify; MCP handles
> agent interoperability; the GraphQL client is isolated behind a circuit breaker because it depends on
> an unofficial API. I also designed around the two main risks: an unstable third-party interface and
> unsafe code execution.
