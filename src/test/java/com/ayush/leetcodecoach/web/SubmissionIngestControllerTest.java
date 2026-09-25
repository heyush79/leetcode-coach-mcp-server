package com.ayush.leetcodecoach.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ayush.leetcodecoach.domain.Attempt;
import com.ayush.leetcodecoach.domain.PracticeSession;
import com.ayush.leetcodecoach.domain.ReviewSchedule;
import com.ayush.leetcodecoach.repository.PracticeRepository;
import com.ayush.leetcodecoach.repository.ReviewRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives the endpoint the browser extension posts to, over real HTTP so the access filter and CORS
 * rules are exercised rather than bypassed.
 *
 * <p>Runs with no LeetCode credentials and remote access off, which is the configuration that
 * matters: it proves the extension path needs nothing from the server, because the cookie stays in
 * the user's browser.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "leetcode.remote-enabled=false",
                "leetcode.session=",
                "leetcode.csrf-token=",
                "leetcode.sync.enabled=false",
                "spring.datasource.url=jdbc:sqlite:file:leetcode-coach-ingest?mode=memory&cache=shared"
        })
class SubmissionIngestControllerTest {

    private static final Instant BASE = Instant.parse("2026-04-01T10:00:00Z");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @Test
    void importsPushedSubmissionsWithoutAnyCredentialsOnTheServer() throws Exception {
        // Two failures then a success, twenty minutes apart: one sitting, graded 3.
        JsonNode report = post(body(
                submission("7001", "coin-change", "Coin Change", "Wrong Answer", BASE),
                submission("7002", "coin-change", "Coin Change", "Wrong Answer", BASE.plusSeconds(600)),
                submission("7003", "coin-change", "Coin Change", "Accepted", BASE.plusSeconds(1200))), 200);

        assertThat(report.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(report.path("run").path("submissionsImported").asInt()).isEqualTo(3);
        assertThat(report.path("message").asString()).contains("browser extension");
        assertThat(report.path("updatedProblems").valueStream().map(JsonNode::asString).toList())
                .containsExactly("coin-change");

        List<PracticeSession> sessions = practiceRepository.findSessionsForProblem("coin-change");
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).source()).isEqualTo(PracticeSession.SOURCE_LEETCODE);
        assertThat(practiceRepository.findAttempts(sessions.get(0).id()))
                .extracting(Attempt::verdict)
                .containsExactly("WRONG_ANSWER", "WRONG_ANSWER", "ACCEPTED");

        ReviewSchedule schedule = reviewRepository.find("coin-change").orElseThrow();
        assertThat(schedule.lastGrade()).isEqualTo(3);
        assertThat(schedule.intervalDays()).isEqualTo(1);
    }

    @Test
    void postingTheSameBatchAgainImportsNothing() throws Exception {
        String payload = body(submission("7101", "two-sum", "Two Sum", "Accepted", BASE));

        assertThat(post(payload, 200).path("run").path("submissionsImported").asInt()).isEqualTo(1);
        ReviewSchedule afterFirst = reviewRepository.find("two-sum").orElseThrow();

        JsonNode second = post(payload, 200);

        assertThat(second.path("run").path("submissionsImported").asInt()).isZero();
        assertThat(second.path("run").path("submissionsSeen").asInt()).isEqualTo(1);
        assertThat(practiceRepository.findSessionsForProblem("two-sum")).hasSize(1);
        assertThat(reviewRepository.find("two-sum").orElseThrow()).isEqualTo(afterFirst);
    }

    @Test
    void acceptsARequestFromABrowserExtensionOrigin() throws Exception {
        HttpResponse<String> response = send(
                request(body(submission("7201", "binary-search", "Binary Search", "Accepted", BASE)))
                        .header("Origin", "chrome-extension://abcdefghijklmnopabcdefghijklmnop")
                        .build());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void refusesARequestFromAWebPage() throws Exception {
        // The reason the Origin check exists: a page the user happens to have open must not be able
        // to write into a server listening on their own machine.
        HttpResponse<String> response = send(
                request(body(submission("7301", "two-sum", "Two Sum", "Accepted", BASE)))
                        .header("Origin", "https://not-your-coach.example")
                        .build());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("Origin is not allowed");
    }

    @Test
    void rejectsAPayloadWithoutSubmissions() throws Exception {
        HttpResponse<String> response = send(request("{}").build());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("submissions");
    }

    @Test
    void rejectsABatchLargerThanTheLimit() throws Exception {
        StringBuilder submissions = new StringBuilder();
        for (int i = 0; i < 501; i++) {
            submissions.append(i == 0 ? "" : ",")
                    .append(submission("9" + i, "two-sum", "Two Sum", "Accepted", BASE));
        }

        HttpResponse<String> response = send(
                request("{\"submissions\":[" + submissions + "]}").build());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("batches");
    }

    @Test
    void reportsAnEmptyBatchAsSkippedRatherThanAsAFailure() throws Exception {
        JsonNode report = post("{\"submissions\":[]}", 200);

        assertThat(report.path("status").asString()).isEqualTo("SKIPPED");
    }

    private JsonNode post(String payload, int expectedStatus) throws Exception {
        HttpResponse<String> response = send(request(payload).build());
        assertThat(response.statusCode())
                .withFailMessage("Expected HTTP %d but got %d: %s", expectedStatus, response.statusCode(), response.body())
                .isEqualTo(expectedStatus);
        return objectMapper.readTree(response.body());
    }

    private HttpRequest.Builder request(String payload) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/sync/submissions"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String body(String... submissions) {
        return "{\"submissions\":[" + String.join(",", submissions) + "]}";
    }

    private static String submission(String id, String slug, String title, String status, Instant at) {
        return "{\"id\":\"" + id + "\",\"title\":\"" + title + "\",\"titleSlug\":\"" + slug + "\","
                + "\"statusDisplay\":\"" + status + "\",\"lang\":\"python3\",\"runtime\":\"52 ms\","
                + "\"memory\":\"16.4 MB\",\"timestamp\":\"" + at.getEpochSecond() + "\","
                + "\"isPending\":\"Not Pending\",\"url\":\"/submissions/detail/" + id + "/\"}";
    }
}
