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
3. `Give me a level-two hint.`
4. `Record my C++ attempt as accepted with O(n*m) time and O(n*m) worst-case space.`
5. `Complete the session and note that I should remember to mark a node visited when enqueuing it.`

Step 5 is the one to narrate. The response carries a `review` block, not just a closed session:

```json
{
  "session": { "status": "COMPLETED", "maxHintLevel": 2 },
  "review": {
    "grade": 3,
    "rationale": "Accepted after 2 hint(s). Recall graded 3/5.",
    "nextReviewInDays": 1,
    "schedule": { "easinessFactor": 2.36, "repetitions": 1, "intervalDays": 1 }
  }
}
```

Two hints cost two grade points. The grade was measured, not asked for.

6. `What topics am I weakest at?` — calls `get_topic_mastery`.
7. `What should I practise next?` — calls `recommend_next_problem`, which prefers a due review, then
   the weakest topic, then anything unattempted. Read the `reason` field aloud.
8. `Show my progress statistics.` — note `reviewsDue`.

## Persistence

Restart the application and ask for progress statistics again. Sessions, attempts, and review
schedules survive, because they are in SQLite rather than in memory.

## Reviews coming due

Reviews are scheduled in days, so a live demo cannot show one maturing. Point at
`McpProtocolIntegrationTest.schedulesAndReportsSpacedRepetitionReviews` instead: it advances an
injected clock by a day and asserts the problem appears in `get_due_reviews` and then outranks every
unattempted problem in `recommend_next_problem`.

## With your account connected

This is the demo that shows the point. Stop the offline process, put your cookie values in `.env`,
and restart with remote enabled:

```bash
set -a && source .env && set +a
LEETCODE_REMOTE_ENABLED=true mvn spring-boot:run
```

Then, in the assistant:

1. `Verify my LeetCode authentication.`
2. Go to leetcode.com and submit something — a wrong answer first, then a fix, is the most
   convincing.
3. `Sync my LeetCode submissions.` The report says how many were imported and which problems were
   rescheduled.
4. `Show my recent practice sessions.` The new sitting is there with `source: LEETCODE`, its
   attempts carrying the real verdicts.
5. `What should I practise today?` The answer now reflects what you just did on the site.

Left alone, step 3 happens every fifteen minutes on its own.

Never screen-share the environment variable values.
