package com.ayush.leetcodecoach.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ayush.leetcodecoach.domain.LeetCodeAuthStatus;
import com.ayush.leetcodecoach.domain.Problem;
import com.ayush.leetcodecoach.service.ProblemCatalogService;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Contract tests for the LeetCode GraphQL integration, against a stubbed endpoint.
 *
 * <p>LeetCode's GraphQL interface is unofficial and cannot be called from CI, but the mapping
 * between its response shape and this application's records is exactly where breakage hides. These
 * tests pin that shape: a field renamed upstream fails here rather than silently binding to null.
 */
@SpringBootTest(properties = {
        "leetcode.remote-enabled=true",
        "spring.datasource.url=jdbc:sqlite:file:leetcode-coach-contract?mode=memory&cache=shared",
        // Trip the breaker quickly so the open-state behaviour is testable.
        "leetcode.minimum-calls=2",
        "leetcode.sliding-window-size=2",
        "leetcode.failure-rate-threshold=50",
        "leetcode.timeout-seconds=2"
})
class LeetCodeGraphQlClientContractTest {

    @RegisterExtension
    static final WireMockExtension LEETCODE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().http2PlainDisabled(true))
            .build();

    @DynamicPropertySource
    static void leetCodeEndpoint(DynamicPropertyRegistry registry) {
        registry.add("leetcode.endpoint", () -> LEETCODE.baseUrl() + "/graphql");
    }

    @Autowired
    private LeetCodeGraphQlClient client;

    @Autowired
    private ProblemCatalogService catalogService;

    @Autowired
    private CircuitBreaker leetCodeCircuitBreaker;

    @BeforeEach
    void resetCircuitBreaker() {
        // The breaker is a singleton shared by the cached context; each test starts closed.
        leetCodeCircuitBreaker.reset();
    }

    @Test
    void mapsAProblemDetailResponse() {
        stubGraphQl("""
                {"data":{"question":{
                  "questionFrontendId":"200",
                  "title":"Number of Islands",
                  "titleSlug":"number-of-islands",
                  "content":"<p>Count islands.</p>",
                  "difficulty":"Medium",
                  "isPaidOnly":false,
                  "status":"ac",
                  "sampleTestCase":"[[\\"1\\"]]",
                  "hints":["Flood fill each component."],
                  "stats":"{\\"acRate\\":\\"61.2%\\"}",
                  "topicTags":[{"name":"Depth-First Search","slug":"depth-first-search"}],
                  "codeSnippets":[{"lang":"Python3","langSlug":"python3","code":"class Solution:"}]
                }}}""");

        RemoteProblem problem = client.fetchProblem("number-of-islands").orElseThrow();

        assertThat(problem.questionFrontendId()).isEqualTo("200");
        assertThat(problem.title()).isEqualTo("Number of Islands");
        assertThat(problem.difficulty()).isEqualTo("Medium");
        assertThat(problem.hints()).containsExactly("Flood fill each component.");
        assertThat(problem.topicTags()).extracting(RemoteProblem.RemoteTopicTag::slug)
                .containsExactly("depth-first-search");
        assertThat(problem.codeSnippets()).extracting(RemoteProblem.RemoteCodeSnippet::langSlug)
                .containsExactly("python3");
    }

    @Test
    void mapsASearchResponseIncludingTheFrontendIdentifier() {
        // Regression: the query used to alias this field, but LeetCode returns a fixed shape and
        // ignores aliases, so every search result bound frontendId to null.
        stubGraphQl("""
                {"data":{"problemsetQuestionListV2":{
                  "total":4055,
                  "questions":[
                    {"questionFrontendId":"1","title":"Two Sum","titleSlug":"two-sum",
                     "difficulty":"EASY","paidOnly":false,"status":"TO_DO","acRate":0.5789,
                     "topicTags":[{"name":"Array","slug":"array"}]}
                  ]}}}""");

        List<RemoteProblemSummary> results = client.searchProblems(null, "EASY", null, 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).questionFrontendId()).isEqualTo("1");
        assertThat(results.get(0).titleSlug()).isEqualTo("two-sum");
    }

    @Test
    void convertsFractionalAcceptanceRatesToPercentages() {
        stubGraphQl("""
                {"data":{"problemsetQuestionListV2":{
                  "total":1,
                  "questions":[
                    {"questionFrontendId":"1","title":"Two Sum","titleSlug":"two-sum",
                     "difficulty":"EASY","paidOnly":false,"status":"TO_DO","acRate":0.5789,
                     "topicTags":[]}
                  ]}}}""");

        List<Problem> results = catalogService.searchProblems(null, "EASY", null, 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).acceptanceRate()).isEqualTo(57.89);
    }

    @Test
    void sendsTheBrowserHeadersLeetCodeExpects() {
        stubGraphQl("""
                {"data":{"problemsetQuestionListV2":{"total":0,"questions":[]}}}""");

        client.searchProblems("two sum", null, null, 5);

        LEETCODE.verify(postRequestedFor(urlPathEqualTo("/graphql"))
                .withHeader("Referer", com.github.tomakehurst.wiremock.client.WireMock.equalTo("https://leetcode.com/"))
                .withHeader("Origin", com.github.tomakehurst.wiremock.client.WireMock.equalTo("https://leetcode.com")));
    }

    @Test
    void reportsGraphQlErrorsAsIntegrationFailures() {
        stubGraphQl("""
                {"errors":[{"message":"That question does not exist"}]}""");

        assertThatThrownBy(() -> client.fetchProblem("not-a-real-problem"))
                .isInstanceOf(LeetCodeIntegrationException.class)
                .hasMessageContaining("not-a-real-problem");
    }

    @Test
    void reportsUpstreamServerErrorsAsIntegrationFailures() {
        LEETCODE.stubFor(post(urlPathEqualTo("/graphql"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client.searchProblems(null, null, null, 5))
                .isInstanceOf(LeetCodeIntegrationException.class);
    }

    @Test
    void anUnauthenticatedSessionIsReportedRatherThanThrown() {
        stubGraphQl("""
                {"data":{"userStatus":{"isSignedIn":false,"username":null,
                 "realName":null,"avatar":null}}}""");

        LeetCodeAuthStatus status = client.verifyAuthentication();

        // No credentials are configured in this context, so the call never reaches the endpoint.
        assertThat(status.credentialsConfigured()).isFalse();
        assertThat(status.authenticated()).isFalse();
    }

    @Test
    void refusesToReadSubmissionsWithoutCredentialsRatherThanReturningAnEmptyList() {
        // LeetCode answers an anonymous submissionList with nulls, which would look like "no
        // submissions". The precondition is checked before any request is made.
        int requestsBefore = LEETCODE.getAllServeEvents().size();

        assertThatThrownBy(() -> client.fetchSubmissions(0, 20, null))
                .isInstanceOf(LeetCodeIntegrationException.class)
                .hasMessageContaining("LEETCODE_SESSION");

        assertThat(LEETCODE.getAllServeEvents()).hasSize(requestsBefore);
    }

    @Test
    void openCircuitBreakerStopsCallingTheFailingEndpoint() {
        LEETCODE.stubFor(post(urlPathEqualTo("/graphql"))
                .willReturn(aResponse().withStatus(500)));

        // Two failures fill the sliding window and trip the breaker.
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> client.searchProblems(null, null, null, 5))
                    .isInstanceOf(LeetCodeIntegrationException.class);
        }

        assertThat(leetCodeCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsBeforeShortCircuit = LEETCODE.getAllServeEvents().size();
        assertThatThrownBy(() -> client.searchProblems(null, null, null, 5))
                .isInstanceOf(LeetCodeIntegrationException.class)
                .hasMessageContaining("circuit breaker is open");

        // The short-circuited call never reached the endpoint.
        assertThat(LEETCODE.getAllServeEvents()).hasSize(callsBeforeShortCircuit);
    }

    @Test
    void anOpenBreakerStillLetsTheCatalogAnswerFromSqlite() {
        LEETCODE.stubFor(post(urlPathEqualTo("/graphql"))
                .willReturn(aResponse().withStatus(500)));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> client.searchProblems(null, null, null, 5))
                    .isInstanceOf(LeetCodeIntegrationException.class);
        }
        assertThat(leetCodeCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // The point of the breaker: tools keep working, served by the seeded catalog.
        assertThat(catalogService.searchProblems(null, "MEDIUM", null, 5)).isNotEmpty();
    }

    private void stubGraphQl(String body) {
        LEETCODE.stubFor(post(urlPathEqualTo("/graphql"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }
}
