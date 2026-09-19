package com.ayush.leetcodecoach.integration;

import com.ayush.leetcodecoach.config.LeetCodeProperties;
import com.ayush.leetcodecoach.domain.LeetCodeAuthStatus;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.stereotype.Component;

@Component
public class LeetCodeGraphQlClient {

    private final HttpSyncGraphQlClient graphQlClient;
    private final LeetCodeProperties properties;
    private final CircuitBreaker circuitBreaker;

    public LeetCodeGraphQlClient(
            HttpSyncGraphQlClient graphQlClient,
            LeetCodeProperties properties,
            CircuitBreaker leetCodeCircuitBreaker) {
        this.graphQlClient = graphQlClient;
        this.properties = properties;
        this.circuitBreaker = leetCodeCircuitBreaker;
    }

    public Optional<RemoteProblem> fetchProblem(String titleSlug) {
        ensureRemoteEnabled();
        RemoteProblem problem = call(
                "Unable to fetch problem '" + titleSlug + "' from LeetCode",
                () -> graphQlClient.documentName("questionData")
                        .variable("titleSlug", titleSlug)
                        .retrieveSync("question")
                        .toEntity(RemoteProblem.class));
        return Optional.ofNullable(problem);
    }

    public List<RemoteProblemSummary> searchProblems(
            String keyword,
            String difficulty,
            String topic,
            int limit) {
        ensureRemoteEnabled();

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("filterCombineType", "ALL");
        if (difficulty != null && !difficulty.isBlank()) {
            filters.put("difficultyFilter", Map.of(
                    "difficulties", List.of(difficulty.trim().toUpperCase()),
                    "operator", "IS"));
        }
        if (topic != null && !topic.isBlank()) {
            filters.put("topicFilter", Map.of(
                    "topicSlugs", List.of(topic.trim().toLowerCase()),
                    "operator", "IS"));
        }

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("categorySlug", "all-code-essentials");
        variables.put("skip", 0);
        variables.put("limit", Math.max(1, Math.min(limit, 50)));
        variables.put("searchKeyword", keyword == null ? "" : keyword.trim());
        variables.put("filters", filters);
        variables.put("sortBy", Map.of(
                "sortField", "CUSTOM",
                "sortOrder", "ASCENDING"));

        RemoteQuestionList result = call(
                "Unable to search LeetCode problems",
                () -> graphQlClient.documentName("problemsetQuestionListV2")
                        .variables(variables)
                        .retrieveSync("problemsetQuestionListV2")
                        .toEntity(RemoteQuestionList.class));
        return result == null || result.questions() == null ? List.of() : result.questions();
    }

    public LeetCodeAuthStatus verifyAuthentication() {
        if (!properties.credentialsConfigured()) {
            return new LeetCodeAuthStatus(
                    false,
                    false,
                    null,
                    null,
                    null,
                    "LEETCODE_SESSION and LEETCODE_CSRF_TOKEN are not configured");
        }
        if (!properties.isRemoteEnabled()) {
            return new LeetCodeAuthStatus(
                    true,
                    false,
                    null,
                    null,
                    null,
                    "LeetCode remote integration is disabled");
        }
        try {
            RemoteUserStatus status = call(
                    "Unable to verify the LeetCode session",
                    () -> graphQlClient.documentName("globalData")
                            .retrieveSync("userStatus")
                            .toEntity(RemoteUserStatus.class));
            boolean authenticated = status != null && Boolean.TRUE.equals(status.isSignedIn());
            return new LeetCodeAuthStatus(
                    true,
                    authenticated,
                    status == null ? null : status.username(),
                    status == null ? null : status.realName(),
                    status == null ? null : status.avatar(),
                    authenticated ? "Authenticated LeetCode session" : "Credentials were supplied but LeetCode did not accept them");
        }
        catch (RuntimeException ex) {
            return new LeetCodeAuthStatus(
                    true,
                    false,
                    null,
                    null,
                    null,
                    "Authentication check failed: " + ex.getMessage());
        }
    }

    /**
     * Runs a GraphQL call through the circuit breaker.
     *
     * <p>A tripped breaker is reported as an ordinary integration failure so that callers keep their
     * single fallback path: whether LeetCode timed out or the breaker declined to try, the answer is
     * the same cached data.
     */
    private <T> T call(String failureMessage, Supplier<T> operation) {
        try {
            return circuitBreaker.executeSupplier(operation);
        }
        catch (CallNotPermittedException ex) {
            throw new LeetCodeIntegrationException(
                    failureMessage + " (circuit breaker is open after repeated failures)", ex);
        }
        catch (RuntimeException ex) {
            throw new LeetCodeIntegrationException(failureMessage, ex);
        }
    }

    private void ensureRemoteEnabled() {
        if (!properties.isRemoteEnabled()) {
            throw new LeetCodeIntegrationException("LeetCode remote integration is disabled");
        }
    }
}
