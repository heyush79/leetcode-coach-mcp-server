# Demo Checklist

## Start

```bash
mkdir -p data
LEETCODE_REMOTE_ENABLED=false mvn spring-boot:run
```

## Verify server

```bash
curl http://127.0.0.1:8080/actuator/health
curl http://127.0.0.1:8080/api/status
```

Expected status includes:

- MCP endpoint `/mcp`
- transport `STREAMABLE_HTTP_WITH_OPTIONAL_SSE`
- six or more cached problems

## Agent prompts

Use these prompts in an MCP-capable client:

1. `Search for three medium graph problems using the LeetCode coach tools.`
2. `Start a 30-minute practice session for number-of-islands. Do not reveal the solution.`
3. `Give me only a level-one hint.`
4. `Record my C++ attempt as accepted with O(n*m) time and O(n*m) worst-case space.`
5. `Complete the session and note that I should remember to mark a node visited when enqueuing it.`
6. `Show my progress statistics.`

## Authentication demo

Stop the offline process, set `LEETCODE_REMOTE_ENABLED=true`, and restart after exporting your own cookie values, restart the app and ask:

`Verify my LeetCode authentication and refresh the two-sum problem.`

Never screen-share the environment variable values.
