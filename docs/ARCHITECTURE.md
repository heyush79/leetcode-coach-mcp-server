# Architecture Notes

## Layering

```text
MCP / REST adapters
        |
Application services
        |
Repositories + GraphQL gateway
        |
SQLite        LeetCode
```

Dependencies point inward toward services and domain records. The MCP adapter does not issue SQL or GraphQL directly.

## Consistency

- Problem synchronization is idempotent by `title_slug`.
- Session and attempt writes use Spring transactions.
- Attempt rows are append-only.
- Session completion is a state transition from `ACTIVE` to `COMPLETED`.

## Availability

The application can start and provide meaningful tools without network access. Seed data makes the first run deterministic. Cached remote records improve over time.

## External API isolation

Only `LeetCodeGraphQlClient` knows query document names or remote DTO shapes. `ProblemCatalogService` maps them into domain records. If the upstream schema changes, the impact is localized.

## Future production design

- PostgreSQL with per-user ownership.
- OAuth 2.0 authorization for MCP clients.
- Redis for rate-limit and hot problem cache.
- Resilience4j circuit breaker around GraphQL.
- Metrics for cache hits, upstream latency, and tool errors.
- Separate sandboxed judge workers connected through a queue.
