# LeetCode Coach MCP Server

> Unofficial educational project. It is not affiliated with or endorsed by LeetCode.

A Java 17 Spring Boot application that exposes coding-practice capabilities to LLM agents through the Model Context Protocol (MCP). It uses Spring AI's Streamable HTTP server, SQLite persistence, and a LeetCode GraphQL client with optional session-cookie authentication.

This repository is intentionally interview-sized: substantial enough to demonstrate protocol integration, persistence, external API handling, and failure design, but not padded with seventeen microservices whose main job is forwarding JSON to one another.

## What it does

- Exposes eleven MCP tools for problem search, session management, hints, attempt tracking, recommendations, and progress.
- Exposes a `leetcode://problem/{titleSlug}` MCP resource.
- Fetches live problem metadata through LeetCode's GraphQL endpoint.
- Adds `LEETCODE_SESSION` and CSRF credentials when configured, allowing authenticated status fields and authentication verification.
- Caches remote data in SQLite and falls back to six seeded problems when LeetCode is unavailable.
- Persists practice sessions and attempts across server restarts.
- Supports optional API-key protection and Origin validation on `/mcp`.
- Includes REST endpoints only for debugging and demonstrations. The actual agent integration is MCP.

## Architecture

```mermaid
flowchart LR
    A[LLM Agent / MCP Client] -->|Streamable HTTP POST/GET; optional SSE| B[Spring AI MCP Server]
    B --> C[Annotated MCP Tools]
    C --> D[Problem Catalog Service]
    C --> E[Practice Service]
    D --> F[Spring GraphQL HTTP Client]
    F --> G[LeetCode GraphQL]
    D --> H[(SQLite)]
    E --> H
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
- JUnit 5, AssertJ, and Mockito

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

## Optional LeetCode authentication

Public problem queries usually work without authentication. To demonstrate authenticated GraphQL integration, configure both values:

```bash
export LEETCODE_SESSION='your-session-cookie'
export LEETCODE_CSRF_TOKEN='your-csrf-cookie'
mvn spring-boot:run
```

You can obtain the cookie values from your own browser session under Developer Tools, Application/Storage, Cookies, `leetcode.com`. Treat both values as credentials.

Then call the MCP tool `verify_leetcode_auth`, or inspect the same behavior through an MCP client.

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
| `complete_practice` | Complete a session with retrospective notes |
| `get_progress_stats` | Return totals, active days, streak, and difficulty split |
| `recommend_next_problem` | Choose an unattempted matching problem |
| `verify_leetcode_auth` | Check the configured LeetCode session |
| `recent_practice_sessions` | List recent sessions |

## Suggested demo

For a deterministic interview demo, start with `LEETCODE_REMOTE_ENABLED=false`; enable the remote client only for the separate GraphQL/authentication segment.

1. Call `search_problems` with `difficulty=MEDIUM` and `topic=graph`.
2. Call `start_practice` for `number-of-islands`.
3. Call `get_hint` at level 1.
4. Call `record_attempt` with a short BFS or DFS implementation and `verdict=ACCEPTED`.
5. Call `complete_practice` with a note about visited-state handling.
6. Call `get_progress_stats`.
7. Restart the application and call `get_progress_stats` again to demonstrate persistence.

## Database schema

### `problems`

Caches normalized remote or seed problem metadata. JSON arrays such as hints, tags, and code snippets are serialized into text columns because SQLite is being used as an embedded cache, not as a platform for analytical joins across topic taxonomies.

### `practice_sessions`

Tracks one coaching session per problem attempt with active/completed state, timestamps, target duration, and notes.

### `attempts`

Stores submitted source code, verdict, optional runtime/memory, complexity claims, and reflection notes.

## Failure handling

- Live search failure: log the upstream error and query SQLite.
- Problem refresh failure: return the cached full problem when available.
- Expired credentials: return an unauthenticated status instead of crashing startup.
- Duplicate problem sync: SQLite `ON CONFLICT(title_slug) DO UPDATE` performs an idempotent upsert.
- Concurrent SQLite writes: Hikari pool size is one because this is an embedded single-node service.
- Invalid session mutation: reject attempts after a session is completed.
- Nullable tool results: records expose optional fields as `@Nullable` and serialize with `NON_NULL`, because Spring AI's generated output schema marks every component required and rejects a null on the wire.

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
- `McpProtocolIntegrationTest` boots the server on a random port and drives the real `/mcp` endpoint over JSON-RPC, asserting that all eleven tools return without a protocol error.

The third layer exists because the first two cannot see the MCP boundary. Spring AI validates every tool result against a generated output schema, so a tool can succeed in Java and still be rejected on the wire. Only a test that speaks the protocol catches that.

A reasonable next extension is a WireMock-based contract test for LeetCode GraphQL responses.

## Main design trade-offs

| Decision | Benefit | Cost |
|---|---|---|
| Synchronous WebMVC MCP server | Simple debugging and JDBC integration | A blocked upstream call occupies a request thread |
| SQLite | Zero-setup persistence and easy demo | Single-node; limited concurrent writers |
| GraphQL query documents | Typed mapping and readable operations | Coupled to an unofficial external schema |
| Cache-first problem detail | Fast and resilient | Data can become stale until refresh |
| No in-process code execution | Safe and small scope | Verdict is supplied externally or by the user |
| Optional API key | Easy local setup with a security path | Not a full OAuth 2.0 MCP authorization implementation |

See `INTERVIEW_GUIDE.md` for the explanation you should give rather than improvising architecture mythology under fluorescent lighting. See `VALIDATION.md` for build and smoke-test commands.
