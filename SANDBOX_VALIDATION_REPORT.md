# Sandbox Validation Report

Generated on 2026-08-03.

## Checks executed in this environment

- Parsed `pom.xml` as XML.
- Parsed JSON and YAML configuration files.
- Compiled the project domain model with `javac --release 17`.
- Compiled all main and test Java sources against local API-shape stubs for the referenced Spring, Spring AI, GraphQL, JDBC, JUnit, Mockito, and AssertJ types.
- Executed `schema.sql` against SQLite, inserted a problem, practice session, and attempt, and verified foreign-key behavior.
- Checked the repository for unresolved `TODO`, `FIXME`, and truncated-content markers.
- Verified that no generated database, credentials, build output, or IDE metadata is included in the archive.

## Limitation

The environment contains Java 17 but does not contain Maven or Docker, and outbound dependency resolution is unavailable. Therefore, `mvn clean verify`, the packaged Spring Boot application, Docker Compose, and the live LeetCode GraphQL call were **not** executed here.

Run this locally before using the project in an interview:

```bash
mvn --batch-mode clean verify
LEETCODE_REMOTE_ENABLED=false mvn spring-boot:run
```

Then follow `docs/DEMO.md`. Treat the live LeetCode GraphQL segment as a separate, best-effort integration check because it is an unofficial external interface.
