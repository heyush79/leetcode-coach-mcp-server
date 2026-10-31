# leetcode-coach-mcp-server

## What this is
- MCP server that records LeetCode practice, syncs the user's own submissions, and schedules reviews with SM-2 spaced repetition. SQLite storage; problem data from LeetCode's unofficial GraphQL with SQLite fallback.
- Java 17, Spring Boot 4.1.0, Spring AI 2.0.0 (`spring-ai-starter-mcp-server-webmvc`, MCP SDK `io.modelcontextprotocol.sdk:mcp` 2.0.0), Jackson 3.1 (`tools.jackson`), sqlite-jdbc, Resilience4j, Spring GraphQL client.
- Transport is Streamable HTTP at `/mcp` (SYNC server), not stdio. `extension/` is a Manifest V3 browser extension that pushes submissions to `/api/sync/submissions`.

## How to run it
Timings from one run on Apple Silicon with a warm `~/.m2`.
- Build + all tests (what CI runs): `mvn --batch-mode clean verify` (= `make verify`). 71 tests, 0 failures, 0 skipped. ~13s on JDK 17, ~21s on JDK 26.
- Same JDK as CI on the dev Mac: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home mvn --batch-mode clean verify`
- One class: `mvn --batch-mode test -Dtest=SpacedRepetitionSchedulerTest` (~2s). One method: `mvn --batch-mode test -Dtest='SpacedRepetitionSchedulerTest#firstSuccessfulRecallSchedulesTomorrow'`. MCP wire tests: `-Dtest=McpProtocolIntegrationTest` (~4s).
- Run offline (no calls to leetcode.com): `make run-offline` (= `mkdir -p data && LEETCODE_REMOTE_ENABLED=false mvn spring-boot:run`). App starts in ~1.2s after compile on `127.0.0.1:8080`.
- Run with live LeetCode: `make run`. Search and `get_problem` then call leetcode.com; submission sync also needs the cookies below.
- Smoke check: `curl http://127.0.0.1:8080/actuator/health`, `curl http://127.0.0.1:8080/api/status`, `curl "http://127.0.0.1:8080/api/problems?difficulty=MEDIUM&limit=5"`.
- Lint/format: none configured (no Spotless/Checkstyle/formatter plugin in `pom.xml`, no `.editorconfig`).
- Docker, unverified (not run): `make docker-up` (= `docker compose up --build`) builds `Dockerfile` from source and publishes `127.0.0.1:8080:8080` with volume `leetcode_coach_data:/app/data`. `Dockerfile.dist` wraps a prebuilt jar for `release.yml`. Published image: `ghcr.io/heyush79/leetcode-coach-mcp-server:latest`.
- MCP client: `.mcp.json` (Claude Code, `mcpServers` key) already points at `http://127.0.0.1:8080/mcp`. `mcp-client.example.json` shows the `servers`-key shape with an `X-API-Key` header, which is only needed when `MCP_API_KEY` is set. Verified with curl: `initialize` + `tools/list` returns 15 tools.
- Env vars: all optional. Defaults are in `src/main/resources/application.yml`; example values with comments are in `.env.example`. `make` does not load `.env`; use `set -a && source .env && set +a`. Main ones: `LEETCODE_SESSION` + `LEETCODE_CSRF_TOKEN` (real account cookies, only for server-side sync and `verify_leetcode_auth`), `LEETCODE_REMOTE_ENABLED`, `MCP_API_KEY`, `MCP_ALLOWED_ORIGIN_HOSTS`, `SERVER_ADDRESS`/`SERVER_PORT`, `SQLITE_URL` (default `jdbc:sqlite:./data/leetcode-coach.db`).
- Tests need no network, Docker, or credentials: in-memory SQLite and WireMock.

## Project layout
Java packages are under `src/main/java/com/ayush/leetcodecoach/`.
- `mcp/`: `LeetCodeCoachTools` (all 15 `@McpTool`s) and `LeetCodeProblemResource` (`leetcode://problem/{titleSlug}`).
- `service/`: `ProblemCatalogService` (remote first, then SQLite) and `PracticeService` (sessions, hints, attempts, recommendations).
- `review/`: pure SM-2 code (`RecallGrader`, `SpacedRepetitionScheduler`); `ReviewService` is the only class here that persists.
- `sync/`: `SubmissionSyncService`, `SubmissionImporter` (infers sittings), `SubmissionParser`, `SubmissionSyncScheduler` (`@Scheduled`).
- `integration/`: `LeetCodeGraphQlClient` and the `Remote*` DTOs. Nothing else knows GraphQL document names.
- `repository/`: `JdbcTemplate` repositories (no JPA). `domain/`: records that tools return.
- `config/`: properties, the two GraphQL clients, the circuit breaker, `McpAccessFilter`, CORS, `SchemaMigrations`, `SeedDataLoader`, `ClockConfig`.
- `web/`: REST endpoints `/api/status`, `/api/problems`, `/api/sync/submissions` (extension ingest), plus `ApiExceptionHandler`.
- `src/main/resources/`: `application.yml`, `schema.sql`, `graphql-documents/*.graphql`.
- `src/test/java/...`: mirrors the main packages. `extension/`: plain JS (`background.js`, `popup.*`), with no build step and no tests.
- `.github/workflows/`: `ci.yml` (verify on pushes to main and on PRs) and `release.yml` (on a `v*` tag: verify, zip the extension, push a multi-arch image to GHCR, create a GitHub release).

## Architecture in short
1. A client POSTs JSON-RPC to `/mcp`. `config/McpAccessFilter` (order -100, on `/mcp`, `/mcp/*`, `/api/sync/*`) returns 403 for a browser `Origin` that is not an allowed host or `chrome-extension://`/`moz-extension://`. When `MCP_API_KEY` is set, it returns 401 unless `X-API-Key` or `Authorization: Bearer` matches.
2. Spring AI dispatches the call to a method in `mcp/LeetCodeCoachTools`. Output schemas are generated from the return records (`generateOutputSchema = true`).
3. Tool methods are thin and delegate to `ProblemCatalogService`, `PracticeService`, `SubmissionSyncService`, or `LeetCodeGraphQlClient.verifyAuthentication()`.
4. `ProblemCatalogService` calls `LeetCodeGraphQlClient` through a Resilience4j breaker (`config/LeetCodeCircuitBreakerConfig`). On `LeetCodeIntegrationException` it falls back to SQLite. Remote results are upserted into `problems`.
5. `PracticeService.completePractice` calls `ReviewService.recordCompletedSession`, which grades recall and rebuilds the SM-2 schedule by replaying all grades. `review_schedule` is a cache over session history.
6. Sync has two inputs that share one pipeline. Pull: `SubmissionSyncScheduler` or the `sync_leetcode_submissions` tool (needs cookies). Push: the extension POSTs to `/api/sync/submissions` (max 500 per request). Both go through `SubmissionSyncService` and `SubmissionImporter` (idempotent on `leetcode_submission_id`), then `ReviewService.rebuildSchedule`.
7. Startup order: `schema.sql` (`spring.sql.init`), then `SchemaMigrations` (`@Order(0)`), then `SeedDataLoader`, which seeds 6 problems when `problems` is empty.

## Conventions
- Packages are split by layer. Tools and controllers stay thin; SQL lives only in `repository/` and GraphQL only in `integration/` (see `docs/ARCHITECTURE.md`).
- Style: 4-space indent, constructor injection into `private final` fields, no Lombok, `catch` on its own line after `}`, SQL in text blocks, explicit imports, records for domain types. Javadoc explains *why*.
- MCP tool names are snake_case (`get_due_reviews`) and Java methods are camelCase. Each param has `@McpToolParam(description=..., required=...)`. Optional params use boxed types, and services apply the defaults and clamps.
- Errors: services throw `IllegalArgumentException` for bad input (REST 400) and `IllegalStateException` for a wrong state (REST 409). Every remote failure becomes `LeetCodeIntegrationException` (REST 502), and callers fall back to cached data. Over MCP, a thrown exception becomes a result with `isError: true` (asserted in `McpProtocolIntegrationTest`).
- Logging: SLF4J `private static final Logger log = LoggerFactory.getLogger(X.class)` to the Spring Boot console. Logging to stdout is safe because the transport is HTTP, not stdio. Never log cookie values; `LeetCodeProperties.credentialShapeProblem()` reports lengths only.
- Time: read "now" from the injected `java.time.Clock` (`config/ClockConfig`) so tests can advance it.
- Tests: JUnit 5 + AssertJ. `review/` and `SubmissionParser` have pure unit tests. `PracticeServiceTest` uses Mockito (`@ExtendWith(MockitoExtension.class)`). `@SpringBootTest` classes each use their own `jdbc:sqlite:file:<name>?mode=memory&cache=shared` and set `leetcode.remote-enabled=false`. `*ContractTest` classes stub leetcode.com with WireMock. HTTP and MCP tests use `RANDOM_PORT`. Test method names are camelCase sentences.
- Commits: imperative, sentence-case subject with no type prefix that states the outcome (e.g. "Return an object from list tools so MCP clients accept the result"). The body explains why, then a `Co-Authored-By:` trailer. Work merges into `main` via PRs from `feat/...` and `fix/...` branches.

## Do NOT
- Rename or remove MCP tools or params, or change a return record's shape, without updating the README tool table, the tool names in the `instructions` in `application.yml`, `docs/DEMO.md`, and `McpProtocolIntegrationTest`.
- Return a bare `List<T>` from a tool. `structuredContent` must be a JSON object, so wrap the list in a record with `of(list)` and `count` (see `domain/ProblemSearchResult`).
- Add a nullable record component without `@Nullable` (`org.jspecify`) and `@JsonInclude(NON_NULL)` on the record. The generated schema otherwise marks it required, and null results fail on the wire.
- Add a column only to `schema.sql`, because existing DB files never get it. Add it in `SchemaMigrations.addColumnIfMissing` too. Indexes on new columns go there, not in `schema.sql`.
- Change `McpAccessFilter.PROTECTED_PATHS` and the URL patterns in `WebFilterConfig` separately, or widen `ExtensionCorsConfig` beyond extension origins and `/api/sync/**`.
- Change the bind defaults (`SERVER_ADDRESS=127.0.0.1`; compose `127.0.0.1:8080:8080`) to all interfaces.
- Change the submission shape on one side only. `SUBMISSION_QUERY` in `extension/background.js` duplicates `graphql-documents/submissionList.graphql`, and the ingest endpoint accepts `RemoteSubmission` unchanged.
- Commit `.env`, real cookie or `MCP_API_KEY` values, or `data/*.db`, or put cookie values in logs or fixtures. Tests use dummy values.
- Write tests that call the real leetcode.com; use WireMock.
- Push `v*` tags or edit `release.yml` without asking: a tag publishes to GHCR and creates a GitHub release.
- Add dependencies or a migration framework without asking. The pom uses core `resilience4j-circuitbreaker` instead of the starter on purpose.
- Reformat whole files. No formatter is configured, so a reformat buries the real change.

## Gotchas
- On the dev Mac `JAVA_HOME` is JDK 26, so `mvn` runs on 26 while `java` on PATH is 17. Both pass; CI uses 17. JDK 26 prints harmless warnings (sqlite-jdbc native access, Mockito dynamic agent).
- Jackson 3: `ObjectMapper` is `tools.jackson.databind.ObjectMapper`, but annotations are still `com.fasterxml.jackson.annotation.*`.
- `spring-boot:run` forks its own JVM (make, then the mvn JVM, then the app JVM). After stopping, confirm nothing is left with `lsof -nP -iTCP:8080 -sTCP:LISTEN`.
- The default DB `./data/leetcode-coach.db` is relative to the working directory (gitignored). Set `SQLITE_URL=jdbc:sqlite:/some/tmp.db` to keep the checkout clean.
- The Hikari pool is a single connection (SQLite).
- With `LEETCODE_REMOTE_ENABLED` at its default `true`, every `search_problems` call tries leetcode.com first. Offline, only the 6 seeded problems exist.
- Background sync runs only when sync is enabled, remote access is enabled, and both cookies are set. It waits 30s, then runs every 900s. The `sync_leetcode_submissions` tool ignores the `enabled` flag.
- There are two `HttpSyncGraphQlClient` beans: the `@Primary` interactive one (8s timeout) and `submissionSyncGraphQlClient` (30s).
- `McpAccessFilter` lets through requests with no `Origin` header (curl, CLI MCP clients), so the API key is the only guard for those.
- Raw MCP over curl needs `Accept: application/json, text/event-stream` and the `Mcp-Session-Id` header returned by `initialize`.
- Expected WARN noise in test output: circuit-breaker state changes, "remote integration is disabled", and a cookie-shape warning caused by the fake cookie in `SubmissionSyncContractTest`.

## Where things are documented
- `README.md`: problem, quick start, tool table, DB schema, failure handling, test layers, trade-offs.
- `docs/ARCHITECTURE.md`: layering, consistency rules, schema evolution. `docs/DEMO.md`: demo checklist and prompts.
- `VALIDATION.md`: build, runtime smoke test, and a manual live-LeetCode check.
- `INTERVIEW_GUIDE.md`: design rationale (SM-2, sync, credential handling, trade-offs).
- `extension/README.md`: install, permissions, troubleshooting. `.env.example`: every env var, with comments.

## Current status / next steps
- `origin/feat/browser-extension-and-docker` (PR #2) and `origin/fix/mcp-tool-output-schema` (PR #1) are fully merged into `main`; no other branches exist.
- No TODO/FIXME in code. `VALIDATION.md` says "thirteen tools", but the server exposes 15.
- The "Future production design" list in `docs/ARCHITECTURE.md` (PostgreSQL, FSRS, OAuth, ...) is ideas only; none of it is started.
