package com.ayush.leetcodecoach.sync;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.ayush.leetcodecoach.domain.Attempt;
import com.ayush.leetcodecoach.domain.CompletionSummary;
import com.ayush.leetcodecoach.domain.Difficulty;
import com.ayush.leetcodecoach.domain.PracticeSession;
import com.ayush.leetcodecoach.domain.Problem;
import com.ayush.leetcodecoach.domain.ReviewSchedule;
import com.ayush.leetcodecoach.domain.SyncReport;
import com.ayush.leetcodecoach.domain.SyncRun;
import com.ayush.leetcodecoach.domain.TopicMastery;
import com.ayush.leetcodecoach.repository.PracticeRepository;
import com.ayush.leetcodecoach.repository.ReviewRepository;
import com.ayush.leetcodecoach.review.ReviewService;
import com.ayush.leetcodecoach.service.PracticeService;
import com.ayush.leetcodecoach.service.ProblemCatalogService;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Drives the submission sync end to end against a stubbed leetcode.com: paging, session
 * inference, grading, schedule rebuild, idempotency, and the failure modes a user will actually
 * hit. The real endpoint needs a personal session cookie and cannot be called from CI.
 */
@SpringBootTest(properties = {
        "leetcode.remote-enabled=true",
        "leetcode.session=test-session-cookie",
        "leetcode.csrf-token=test-csrf-token",
        // The background scheduler must not race the explicit calls below.
        "leetcode.sync.enabled=false",
        "leetcode.sync.page-size=2",
        "leetcode.sync.max-pages=10",
        // Repeated 500s in the stub-failure test must not trip the breaker for later tests.
        "leetcode.minimum-calls=1000",
        "leetcode.timeout-seconds=2",
        "spring.datasource.url=jdbc:sqlite:file:leetcode-coach-sync?mode=memory&cache=shared"
})
class SubmissionSyncContractTest {

    private static final Instant BASE = Instant.parse("2026-03-01T10:00:00Z");

    @RegisterExtension
    static final WireMockExtension LEETCODE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().http2PlainDisabled(true))
            .build();

    @DynamicPropertySource
    static void leetCodeEndpoint(DynamicPropertyRegistry registry) {
        registry.add("leetcode.endpoint", () -> LEETCODE.baseUrl() + "/graphql");
    }

    @Autowired
    private SubmissionSyncService syncService;

    @Autowired
    private PracticeService practiceService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private ProblemCatalogService catalogService;

    @Autowired
    private CircuitBreaker leetCodeCircuitBreaker;

    @BeforeEach
    void reset() {
        LEETCODE.resetAll();
        leetCodeCircuitBreaker.reset();
    }

    @Test
    void importsPagedHistoryIntoGradedSessions() {
        // Newest first, as LeetCode lists them. Three coin-change submissions twenty minutes apart
        // are one sitting; add-two-numbers three days earlier is not in the seed catalog.
        stubSubmissionPages(
                page("key-1", true,
                        submission("903", "coin-change", "Coin Change", "Accepted", BASE.plusSeconds(1200)),
                        submission("902", "coin-change", "Coin Change", "Wrong Answer", BASE.plusSeconds(600)),
                        pendingSubmission("904", "coin-change", "Coin Change", BASE.plusSeconds(1500))),
                page(null, false,
                        submission("901", "coin-change", "Coin Change", "Wrong Answer", BASE),
                        submission("900", "add-two-numbers", "Add Two Numbers", "Accepted", BASE.minusSeconds(3 * 86400))));
        stubProblemDetail("add-two-numbers", "Add Two Numbers", "Medium",
                "[{\"name\":\"Linked List\",\"slug\":\"linked-list\"},{\"name\":\"Math\",\"slug\":\"math\"}]");

        SyncReport report = syncService.sync(false);

        assertThat(report.status()).isEqualTo(SyncRun.STATUS_SUCCEEDED);
        assertThat(report.run().submissionsSeen()).isEqualTo(5);
        assertThat(report.run().submissionsImported()).isEqualTo(4);
        assertThat(report.run().sessionsCreated()).isEqualTo(2);
        assertThat(report.run().sessionsUpdated()).isEqualTo(2);
        assertThat(report.run().problemsAdded()).isEqualTo(1);
        assertThat(report.updatedProblems()).containsExactlyInAnyOrder("coin-change", "add-two-numbers");

        // One synced session spanning the sitting, attempts in the order they were made.
        List<PracticeSession> sessions = practiceRepository.findSessionsForProblem("coin-change");
        assertThat(sessions).hasSize(1);
        PracticeSession sitting = sessions.get(0);
        assertThat(sitting.source()).isEqualTo(PracticeSession.SOURCE_LEETCODE);
        assertThat(sitting.status()).isEqualTo("COMPLETED");
        assertThat(sitting.startedAt()).isEqualTo(BASE);
        assertThat(sitting.completedAt()).isEqualTo(BASE.plusSeconds(1200));
        assertThat(practiceRepository.findAttempts(sitting.id()))
                .extracting(Attempt::verdict)
                .containsExactly("WRONG_ANSWER", "WRONG_ANSWER", "ACCEPTED");
        assertThat(practiceRepository.findAttempts(sitting.id()))
                .extracting(Attempt::leetcodeSubmissionId)
                .containsExactly("901", "902", "903");

        // Two failures before the accepted attempt: 5 - 2 = grade 3, first review tomorrow.
        ReviewSchedule schedule = reviewRepository.find("coin-change").orElseThrow();
        assertThat(schedule.lastGrade()).isEqualTo(3);
        assertThat(schedule.repetitions()).isEqualTo(1);
        assertThat(schedule.intervalDays()).isEqualTo(1);
        assertThat(schedule.dueAt()).isEqualTo(Instant.parse("2026-03-02T00:00:00Z"));

        // The unknown problem was fetched in full, so it counts towards topic mastery.
        Problem added = catalogService.findCached("add-two-numbers").orElseThrow();
        assertThat(added.difficulty()).isEqualTo(Difficulty.MEDIUM);
        assertThat(reviewRepository.find("add-two-numbers").orElseThrow().lastGrade()).isEqualTo(5);
        assertThat(reviewService.topicMastery()).extracting(TopicMastery::topicSlug).contains("linked-list");

        // The request carried the account cookie LeetCode needs to answer at all.
        LEETCODE.verify(postRequestedFor(urlPathEqualTo("/graphql"))
                .withRequestBody(containing("submissionList"))
                .withHeader("Cookie", containing("LEETCODE_SESSION=test-session-cookie"))
                .withHeader("x-csrftoken", containing("test-csrf-token")));
    }

    @Test
    void syncingAgainImportsNothingAndChangesNothing() {
        stubSubmissionPages(page(null, false,
                submission("911", "two-sum", "Two Sum", "Accepted", BASE.plusSeconds(300)),
                submission("910", "two-sum", "Two Sum", "Wrong Answer", BASE)));

        SyncReport first = syncService.sync(false);
        ReviewSchedule afterFirst = reviewRepository.find("two-sum").orElseThrow();
        SyncReport second = syncService.sync(false);

        assertThat(first.run().submissionsImported()).isEqualTo(2);
        assertThat(second.run().submissionsImported()).isZero();
        assertThat(second.run().submissionsSeen()).isEqualTo(2);
        assertThat(second.message()).contains("No new submissions");
        assertThat(practiceRepository.findSessionsForProblem("two-sum")).hasSize(1);
        assertThat(reviewRepository.find("two-sum").orElseThrow()).isEqualTo(afterFirst);
    }

    @Test
    void attachesASubmissionMadeDuringACoachedSessionToThatSession() {
        // The user starts a sitting with the assistant, then submits on leetcode.com. The real
        // verdict belongs with the hints they used, not in a separate synced session.
        PracticeSession coached = practiceService.startPractice("lru-cache", 30);
        stubSubmissionPages(page(null, false,
                submission("920", "lru-cache", "LRU Cache", "Accepted", coached.startedAt().plusSeconds(5))));

        SyncReport report = syncService.sync(false);

        assertThat(report.run().sessionsCreated()).isZero();
        assertThat(report.run().sessionsUpdated()).isEqualTo(1);
        assertThat(practiceRepository.findSessionsForProblem("lru-cache")).hasSize(1);
        assertThat(practiceRepository.findAttempts(coached.id()))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.source()).isEqualTo(Attempt.SOURCE_LEETCODE);
                    assertThat(attempt.verdict()).isEqualTo("ACCEPTED");
                });

        // Completing the coached session grades from the synced verdict: accepted, no hints.
        CompletionSummary completion = practiceService.completePractice(coached.id(), "Solved on the site");
        assertThat(completion.review().grade()).isEqualTo(5);
        assertThat(completion.review().rationale()).contains("unaided");
    }

    @Test
    void fullSyncBackfillsPastSubmissionsThatAreAlreadyKnown() {
        stubSubmissionPages(page(null, false,
                submission("930", "binary-search", "Binary Search", "Accepted", BASE)));
        assertThat(syncService.sync(false).run().submissionsImported()).isEqualTo(1);

        // Now the listing also holds two older submissions the first run never reached.
        stubSubmissionPages(
                page("key-1", true,
                        submission("930", "binary-search", "Binary Search", "Accepted", BASE),
                        submission("931", "binary-search", "Binary Search", "Wrong Answer", BASE.minusSeconds(86400))),
                page(null, false,
                        submission("932", "binary-search", "Binary Search", "Wrong Answer", BASE.minusSeconds(2 * 86400))));

        // Incremental: the first page contains a known submission, so paging stops there.
        SyncReport incremental = syncService.sync(false);
        assertThat(incremental.run().submissionsImported()).isEqualTo(1);
        assertThat(incremental.run().submissionsSeen()).isEqualTo(2);

        // Full: keeps paging past known submissions and finds the oldest one.
        LEETCODE.resetScenarios();
        SyncReport full = syncService.sync(true);
        assertThat(full.run().submissionsImported()).isEqualTo(1);
        assertThat(full.run().submissionsSeen()).isEqualTo(3);

        // Three sittings a day apart: two failures, then a success, replayed in time order.
        assertThat(practiceRepository.findSessionsForProblem("binary-search")).hasSize(3);
        ReviewSchedule schedule = reviewRepository.find("binary-search").orElseThrow();
        assertThat(schedule.lastGrade()).isEqualTo(5);
        assertThat(schedule.repetitions()).isEqualTo(1);
        assertThat(schedule.lastReviewedAt()).isEqualTo(BASE);
    }

    @Test
    void storesAStubWhenProblemDetailCannotBeFetchedSoNoSubmissionIsLost() {
        stubSubmissionPages(page(null, false,
                submission("940", "obscure-problem", "Obscure Problem", "Accepted", BASE)));
        LEETCODE.stubFor(post(urlPathEqualTo("/graphql"))
                .withRequestBody(containing("questionData"))
                .willReturn(aResponse().withStatus(500)));

        SyncReport report = syncService.sync(false);

        assertThat(report.status()).isEqualTo(SyncRun.STATUS_SUCCEEDED);
        assertThat(report.run().problemsAdded()).isEqualTo(1);
        Problem stub = catalogService.findCached("obscure-problem").orElseThrow();
        assertThat(stub.title()).isEqualTo("Obscure Problem");
        assertThat(stub.difficulty()).isEqualTo(Difficulty.UNKNOWN);
        assertThat(reviewRepository.find("obscure-problem")).isPresent();
    }

    @Test
    void reportsAnExpiredCookieAsAFailureTheUserCanActOn() {
        // What LeetCode returns to a caller it does not recognise: nulls, not an error.
        LEETCODE.stubFor(post(urlPathEqualTo("/graphql"))
                .withRequestBody(containing("submissionList"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":{\"submissionList\":{\"lastKey\":null,\"hasNext\":null,\"submissions\":null}}}")));

        SyncReport report = syncService.sync(false);

        assertThat(report.status()).isEqualTo(SyncRun.STATUS_FAILED);
        assertThat(report.message()).contains("expired");
        assertThat(syncService.status().lastRun().status()).isEqualTo(SyncRun.STATUS_FAILED);
    }

    /** Serves the given pages in order, one per request, the way LeetCode's continuation works. */
    private void stubSubmissionPages(String... pages) {
        for (int i = 0; i < pages.length; i++) {
            String state = i == 0 ? Scenario.STARTED : "page-" + i;
            String nextState = i + 1 < pages.length ? "page-" + (i + 1) : state;
            LEETCODE.stubFor(post(urlPathEqualTo("/graphql"))
                    .withRequestBody(containing("submissionList"))
                    .inScenario("submission-paging")
                    .whenScenarioStateIs(state)
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(pages[i]))
                    .willSetStateTo(nextState));
        }
    }

    private void stubProblemDetail(String slug, String title, String difficulty, String topicTagsJson) {
        LEETCODE.stubFor(post(urlPathEqualTo("/graphql"))
                .withRequestBody(containing("questionData"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":{\"question\":{"
                                + "\"questionFrontendId\":\"2\",\"title\":\"" + title + "\",\"titleSlug\":\"" + slug + "\","
                                + "\"content\":\"<p>Statement.</p>\",\"difficulty\":\"" + difficulty + "\","
                                + "\"isPaidOnly\":false,\"status\":null,\"sampleTestCase\":\"[1]\",\"hints\":[],"
                                + "\"stats\":\"{}\",\"topicTags\":" + topicTagsJson + ",\"codeSnippets\":[]}}}")));
    }

    private static String page(String lastKey, boolean hasNext, String... submissions) {
        return "{\"data\":{\"submissionList\":{\"lastKey\":" + (lastKey == null ? "null" : "\"" + lastKey + "\"")
                + ",\"hasNext\":" + hasNext + ",\"submissions\":[" + String.join(",", submissions) + "]}}}";
    }

    private static String submission(String id, String slug, String title, String status, Instant at) {
        return "{\"id\":\"" + id + "\",\"title\":\"" + title + "\",\"titleSlug\":\"" + slug + "\","
                + "\"statusDisplay\":\"" + status + "\",\"lang\":\"python3\",\"runtime\":\"52 ms\","
                + "\"memory\":\"16.4 MB\",\"timestamp\":\"" + at.getEpochSecond() + "\","
                + "\"isPending\":\"Not Pending\",\"url\":\"/submissions/detail/" + id + "/\"}";
    }

    private static String pendingSubmission(String id, String slug, String title, Instant at) {
        return "{\"id\":\"" + id + "\",\"title\":\"" + title + "\",\"titleSlug\":\"" + slug + "\","
                + "\"statusDisplay\":\"\",\"lang\":\"python3\",\"runtime\":\"N/A\",\"memory\":\"N/A\","
                + "\"timestamp\":\"" + at.getEpochSecond() + "\",\"isPending\":\"Pending\",\"url\":null}";
    }
}
