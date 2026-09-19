package com.ayush.leetcodecoach;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives the real Streamable HTTP MCP endpoint over JSON-RPC.
 *
 * <p>The service-level tests call Java methods directly, so they cannot observe the MCP layer:
 * tool registration, argument binding, and — the reason this class exists — output schema
 * validation. A tool whose result carries a null field is rejected by the protocol even though
 * the underlying service returned normally, so every tool is asserted through the wire here.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "leetcode.remote-enabled=false",
                "spring.datasource.url=jdbc:sqlite:file:leetcode-coach-mcp-protocol?mode=memory&cache=shared",
                // Lets AdjustableClockConfig replace the application's system clock.
                "spring.main.allow-bean-definition-overriding=true"
        })
@Import(McpProtocolIntegrationTest.AdjustableClockConfig.class)
class McpProtocolIntegrationTest {

    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @LocalServerPort
    private int port;

    private String sessionId;

    @BeforeEach
    void resetClock() {
        AdjustableClockConfig.now.set(Instant.parse("2026-03-10T09:00:00Z"));
    }

    @BeforeEach
    void initializeSession() throws Exception {
        JsonNode initialize = rpc("initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "integration-test", "version", "1.0.0")), 1);

        assertThat(initialize.path("result").path("serverInfo").path("name").asString())
                .isEqualTo("leetcode-coach");
        assertThat(sessionId).isNotBlank();

        rpc("notifications/initialized", null, null);
    }

    @Test
    void exposesEveryToolOverTheProtocol() throws Exception {
        JsonNode tools = rpc("tools/list", Map.of(), 2).path("result").path("tools");

        assertThat(tools.valueStream().map(tool -> tool.path("name").asString()).toList())
                .containsExactlyInAnyOrder(
                        "search_problems", "get_problem", "start_practice", "get_session_context",
                        "get_hint", "record_attempt", "complete_practice", "get_progress_stats",
                        "recommend_next_problem", "verify_leetcode_auth", "recent_practice_sessions",
                        "get_due_reviews", "get_topic_mastery", "sync_leetcode_submissions", "get_sync_status");
    }

    @Test
    void runsTheFullCoachingWorkflowWithoutProtocolErrors() throws Exception {
        // A freshly started session has a null completedAt and null notes, and a seeded problem has
        // a null acceptanceRate. Each one previously failed output schema validation at this layer.
        assertThat(callTool("get_problem", Map.of("titleSlug", "number-of-islands")).path("title").asString())
                .isEqualTo("Number of Islands");

        JsonNode session = callTool("start_practice",
                Map.of("titleSlug", "number-of-islands", "targetMinutes", 30));
        String practiceSessionId = session.path("id").asString();
        assertThat(practiceSessionId).isNotBlank();
        assertThat(session.path("status").asString()).isEqualTo("ACTIVE");

        JsonNode hint = callTool("get_hint", Map.of("sessionId", practiceSessionId, "level", 1));
        assertThat(hint.path("hints")).hasSize(1);

        JsonNode attempt = callTool("record_attempt", Map.of(
                "sessionId", practiceSessionId,
                "language", "python",
                "code", "def num_islands(grid): return 0",
                "verdict", "ACCEPTED"));
        assertThat(attempt.path("verdict").asString()).isEqualTo("ACCEPTED");

        assertThat(callTool("get_session_context", Map.of("sessionId", practiceSessionId))
                .path("attempts")).hasSize(1);

        JsonNode completion = callTool("complete_practice",
                Map.of("sessionId", practiceSessionId, "notes", "Mark cells visited when enqueuing"));
        assertThat(completion.path("session").path("status").asString()).isEqualTo("COMPLETED");

        // Solved on the first attempt after one hint, so recall grades 4 and the review is scheduled.
        assertThat(completion.path("review").path("grade").asInt()).isEqualTo(4);
        assertThat(completion.path("review").path("nextReviewInDays").asInt()).isEqualTo(1);
        assertThat(completion.path("review").path("rationale").asString()).contains("1 hint");

        JsonNode stats = callTool("get_progress_stats", Map.of());
        assertThat(stats.path("completedSessions").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(stats.path("acceptedAttempts").asLong()).isGreaterThanOrEqualTo(1);

        assertThat(callTool("recent_practice_sessions", Map.of()).path("sessions")).isNotEmpty();
    }

    @Test
    void schedulesAndReportsSpacedRepetitionReviews() throws Exception {
        String practiceSessionId = callTool("start_practice",
                Map.of("titleSlug", "coin-change", "targetMinutes", 30)).path("id").asString();

        callTool("record_attempt", Map.of(
                "sessionId", practiceSessionId,
                "language", "python",
                "code", "def coin_change(coins, amount): return -1",
                "verdict", "WRONG_ANSWER"));

        JsonNode completion = callTool("complete_practice",
                Map.of("sessionId", practiceSessionId, "notes", "Revisit the DP state"));

        // Nothing was accepted, so recall grades 1 and the problem is scheduled to retry tomorrow.
        assertThat(completion.path("review").path("grade").asInt()).isEqualTo(1);
        assertThat(completion.path("review").path("nextReviewInDays").asInt()).isEqualTo(1);
        assertThat(callTool("get_due_reviews", Map.of("limit", 10)).path("reviews")).isEmpty();

        AdjustableClockConfig.advanceDays(1);

        assertThat(callTool("get_due_reviews", Map.of("limit", 10)).path("reviews")
                .valueStream().map(review -> review.path("titleSlug").asString()).toList())
                .contains("coin-change");

        assertThat(callTool("get_topic_mastery", Map.of()).path("topics")
                .valueStream().map(topic -> topic.path("topicSlug").asString()).toList())
                .contains("dynamic-programming");

        // A due review outranks an unattempted problem.
        assertThat(callTool("recommend_next_problem", Map.of()).path("reason").asString())
                .contains("Due for spaced-repetition review");

        assertThat(callTool("get_progress_stats", Map.of()).path("reviewsDue").asLong())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void returnsResultsForToolsWithNullableFieldsWhileOffline() throws Exception {
        // Remote GraphQL is disabled, so these exercise the SQLite fallback paths.
        assertThat(callTool("search_problems", Map.of("difficulty", "MEDIUM", "limit", 5)).path("problems"))
                .isNotEmpty();

        assertThat(callTool("recommend_next_problem", Map.of("difficulty", "EASY"))
                .path("problem").path("titleSlug").asString())
                .isNotBlank();

        assertThat(callTool("verify_leetcode_auth", Map.of())
                .path("credentialsConfigured").asBoolean())
                .isFalse();
    }

    @Test
    void everyToolReturnsStructuredContentThatIsAJsonObject() throws Exception {
        // Regression: tools returning List<T> put a top-level array in structuredContent, which MCP
        // forbids. Claude Code rejected four tools this way while the server reported success.
        for (String name : List.of("search_problems", "get_due_reviews", "get_topic_mastery",
                "recent_practice_sessions", "get_progress_stats", "verify_leetcode_auth",
                "sync_leetcode_submissions", "get_sync_status")) {
            Map<String, Object> arguments = name.equals("search_problems")
                    ? Map.of("difficulty", "EASY", "limit", 3)
                    : Map.of();
            callTool(name, arguments);
        }
    }

    @Test
    void explainsWhySubmissionSyncIsUnavailableInsteadOfFailing() throws Exception {
        // Remote access is off in this context, so the sync must decline with a reason the agent
        // can relay, not a protocol error.
        JsonNode report = callTool("sync_leetcode_submissions", Map.of());
        assertThat(report.path("status").asString()).isEqualTo("SKIPPED");
        assertThat(report.path("message").asString()).contains("disabled");

        JsonNode status = callTool("get_sync_status", Map.of());
        assertThat(status.path("credentialsConfigured").asBoolean()).isFalse();
        assertThat(status.path("syncedSubmissions").asLong()).isZero();
        assertThat(status.path("explanation").asString()).isNotBlank();
    }

    @Test
    void reportsToolErrorsWithoutBreakingTheSession() throws Exception {
        JsonNode result = rpc("tools/call",
                Map.of("name", "get_session_context", "arguments", Map.of("sessionId", "does-not-exist")), 10)
                .path("result");

        assertThat(result.path("isError").asBoolean()).isTrue();

        // The session must still be usable after a failed tool call.
        assertThat(callTool("get_progress_stats", Map.of()).path("totalAttempts").asLong())
                .isGreaterThanOrEqualTo(0);
    }

    /** Calls a tool and returns its parsed structured result, failing if the protocol reported an error. */
    private JsonNode callTool(String name, Map<String, Object> arguments) throws Exception {
        JsonNode result = rpc("tools/call", Map.of("name", name, "arguments", arguments), 3).path("result");
        String text = result.path("content").path(0).path("text").asString();

        assertThat(result.path("isError").asBoolean())
                .withFailMessage("Tool '%s' returned a protocol error: %s", name, text)
                .isFalse();

        // MCP requires structuredContent to be a JSON object. A tool returning a bare List produces a
        // top-level array here, which spec-compliant clients reject even though the call "succeeded".
        if (result.has("structuredContent")) {
            assertThat(result.path("structuredContent").isObject())
                    .withFailMessage(
                            "Tool '%s' returned structuredContent that is not a JSON object: %s",
                            name, result.path("structuredContent"))
                    .isTrue();
        }

        return objectMapper.readTree(text);
    }

    private JsonNode rpc(String method, Map<String, Object> params, Integer id) throws Exception {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("jsonrpc", "2.0");
        payload.put("method", method);
        if (id != null) {
            payload.put("id", id);
        }
        if (params != null) {
            payload.put("params", params);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));

        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId);
        }

        HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .withFailMessage("%s returned HTTP %d: %s", method, response.statusCode(), response.body())
                .isBetween(200, 299);

        response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> this.sessionId = value);

        return parse(response.body(), method);
    }

    /** Streamable HTTP may answer with plain JSON or with a single SSE {@code data:} frame. */
    private JsonNode parse(String body, String method) throws IOException {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        for (String line : body.split("\\R")) {
            if (line.startsWith("data:")) {
                return objectMapper.readTree(line.substring(5).trim());
            }
        }
        JsonNode node = objectMapper.readTree(body);
        assertThat(node.has("error"))
                .withFailMessage("%s returned a JSON-RPC error: %s", method, body)
                .isFalse();
        return node;
    }

    /**
     * Replaces the application clock so the test can advance days instead of waiting for them.
     * Review scheduling is measured in days, which is otherwise untestable in a single run.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class AdjustableClockConfig {

        static final AtomicReference<Instant> now =
                new AtomicReference<>(Instant.parse("2026-03-10T09:00:00Z"));

        static void advanceDays(int days) {
            now.updateAndGet(instant -> instant.plus(Duration.ofDays(days)));
        }

        @Bean
        Clock clock() {
            return new Clock() {

                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return now.get();
                }
            };
        }
    }
}
