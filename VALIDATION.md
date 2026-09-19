# Validation Guide

## Automated project build

Run the complete build locally or in GitHub Actions:

```bash
mvn --batch-mode clean verify
```

This compiles Java 17 source, runs unit tests, runs the Spring Boot + SQLite integration test, and builds the executable JAR.

## Runtime smoke test

Start the application with remote access disabled so the result is deterministic:

```bash
mkdir -p data
LEETCODE_REMOTE_ENABLED=false mvn spring-boot:run
```

In another terminal:

```bash
curl --fail http://127.0.0.1:8080/actuator/health
curl --fail http://127.0.0.1:8080/api/status
curl --fail "http://127.0.0.1:8080/api/problems?difficulty=MEDIUM&limit=5"
```

Then connect an MCP client to `http://127.0.0.1:8080/mcp`, list tools, and run the workflow in `docs/DEMO.md`.

## What is covered

- Plain Java domain compilation on Java 17.
- SQLite schema creation and foreign-key behavior.
- Seed-data startup without network access.
- Catalog fallback when remote GraphQL is disabled.
- Practice-session, attempt, completion, and progress persistence.
- The live `/mcp` endpoint over JSON-RPC: initialize, `tools/list`, and every one of the eleven tools, including output schema validation and tool-error reporting.
- Maven build in GitHub Actions on every push and pull request.

## What the automated tests cannot cover

The live LeetCode GraphQL call is excluded on purpose. It depends on an unofficial third-party
schema and on credentials that expire, so it is verified by hand using the steps below rather
than allowed to make CI fail for reasons unrelated to this code.

## External integration check

LeetCode GraphQL is intentionally tested separately because it is a third-party interface and can change independently:

```bash
export LEETCODE_SESSION='...'
export LEETCODE_CSRF_TOKEN='...'
mvn spring-boot:run
```

Use the MCP tool `verify_leetcode_auth`, then call `get_problem` with `refresh=true`. Never put the cookie values in logs, screenshots, test fixtures, or commits.
