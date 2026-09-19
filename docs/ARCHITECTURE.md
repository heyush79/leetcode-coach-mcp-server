# Architecture Notes

## Layering

```text
MCP / REST adapters
        |
Application services  ->  Review engine (pure: grader + SM-2 scheduler)
        |
Repositories + GraphQL gateway (circuit breaker)
        |
SQLite        LeetCode
```

The review engine holds no state and touches no infrastructure. `RecallGrader` maps observed session
signals to a grade; `SpacedRepetitionScheduler` maps a grade plus the previous schedule to the next
one. `ReviewService` is the only part that persists anything, which keeps the arithmetic unit-testable
without a database or a clock.

Dependencies point inward toward services and domain records. The MCP adapter does not issue SQL or GraphQL directly.

## Consistency

- Problem synchronization is idempotent by `title_slug`.
- Session and attempt writes use Spring transactions.
- Attempt rows are append-only.
- Session completion is a state transition from `ACTIVE` to `COMPLETED`.
- Completion is what advances a review schedule, and only on the transition, so repeating the call cannot inflate intervals.
- `review_schedule` holds one row per problem, upserted by `title_slug`.

## Availability

The application can start and provide meaningful tools without network access. Seed data makes the first run deterministic. Cached remote records improve over time.

## Time

Anything that reads "now" takes an injected `Clock`. Review scheduling is entirely about elapsed days,
so the current instant is an input to the domain rather than an ambient fact, and tests advance time
instead of sleeping through it.

## External API isolation

Only `LeetCodeGraphQlClient` knows query document names or remote DTO shapes. `ProblemCatalogService` maps them into domain records. If the upstream schema changes, the impact is localized.

A Resilience4j circuit breaker wraps every remote call. An open breaker surfaces as an ordinary
`LeetCodeIntegrationException`, so callers keep a single fallback path: whether LeetCode timed out or
the breaker declined to try, the answer is the same cached data.

## Schema evolution

`schema.sql` runs on every start with `CREATE TABLE IF NOT EXISTS`, which is idempotent but skips
tables that already exist. `SchemaMigrations` adds missing columns behind a `PRAGMA table_info` check
so an existing database keeps its practice history. A service with more than one deployment target
belongs on Flyway or Liquibase.

## Future production design

- PostgreSQL with per-user ownership.
- FSRS in place of SM-2, once there is enough outcome data to fit it.
- OAuth 2.0 authorization for MCP clients.
- Redis for rate-limit and hot problem cache.
- Metrics for cache hits, upstream latency, and tool errors.
- Separate sandboxed judge workers connected through a queue.
