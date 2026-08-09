# Interview Guide: LeetCode Coach MCP Server

## 90-second explanation

> I built a Java 17 Spring Boot MCP server that gives LLM agents structured coding-practice tools. Instead of prompting an LLM with unstructured text, the agent can call tools such as `search_problems`, `start_practice`, `get_hint`, `record_attempt`, and `get_progress_stats`.
>
> Spring AI exposes the annotated Java methods over MCP using Streamable HTTP. Problem metadata comes from LeetCode's GraphQL endpoint through Spring for GraphQL. When LeetCode credentials are configured, the client sends the user's session and CSRF cookies and can verify the authenticated session. SQLite caches problems and persists practice sessions and attempts.
>
> I designed the integration cache-first. If LeetCode is unavailable, the server can still use cached and seeded problems. I also avoided executing arbitrary user code inside the service because that requires a sandboxed judge architecture. The MCP layer is responsible for structured tool access, while services contain the business logic and repositories own persistence.

## Resume bullet you can defend

**LeetCode Coach MCP Server | Java 17, Spring Boot, Spring AI, SQLite, GraphQL**

- Built a Spring AI MCP server exposing 11 coding-practice tools over Streamable HTTP with optional SSE responses, SQLite-backed session persistence, cache-first LeetCode GraphQL synchronization, and cookie/CSRF authenticated profile verification.

Use “Streamable HTTP with optional SSE responses” in conversation. Saying “HTTP/SSE transport” is understandable shorthand, but the current MCP transport is Streamable HTTP; the old two-endpoint SSE transport is deprecated.

## Component map

| Component | Responsibility |
|---|---|
| `LeetCodeCoachTools` | MCP tool contract and parameter descriptions |
| `LeetCodeProblemResource` | MCP resource at `leetcode://problem/{titleSlug}` |
| `ProblemCatalogService` | Cache-first retrieval, normalization, fallback, recommendations |
| `PracticeService` | Session lifecycle, hints, attempts, streak, statistics |
| `LeetCodeGraphQlClient` | Executes named GraphQL documents and maps remote DTOs |
| `ProblemRepository` | SQLite problem upsert and search |
| `PracticeRepository` | SQLite sessions, attempts, and aggregate queries |
| `McpAccessFilter` | Optional API key and Origin-host validation |
| `SeedDataLoader` | Offline demo catalog |

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

Unit tests mock repositories and the catalog to verify session creation, hint progression, and streak calculation. An integration test boots the application with remote GraphQL disabled, initializes an in-memory SQLite database, seeds the catalog, and runs a complete practice workflow. The next layer would add WireMock fixtures for GraphQL contract tests.

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
2. Connect an MCP client to `/mcp` and list tools.
3. Search for a medium graph problem.
4. Start `number-of-islands`; save the returned session ID.
5. Request level-one hint and explain progressive disclosure.
6. Record an attempt and complete the session.
7. Fetch progress statistics.
8. Restart the server and fetch statistics again to prove SQLite persistence.
9. With credentials configured, call `verify_leetcode_auth` and refresh a live problem.

## Thirty-second closing statement

> The project demonstrates that I can integrate an emerging protocol without putting protocol details into business logic. MCP handles agent interoperability, GraphQL handles structured upstream data, and SQLite provides resilient local state. I also designed around the two main risks: an unstable third-party API and unsafe code execution.
