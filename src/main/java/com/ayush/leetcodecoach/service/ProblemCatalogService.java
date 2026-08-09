package com.ayush.leetcodecoach.service;

import com.ayush.leetcodecoach.domain.CodeSnippet;
import com.ayush.leetcodecoach.domain.Difficulty;
import com.ayush.leetcodecoach.domain.Problem;
import com.ayush.leetcodecoach.domain.TopicTag;
import com.ayush.leetcodecoach.integration.LeetCodeGraphQlClient;
import com.ayush.leetcodecoach.integration.LeetCodeIntegrationException;
import com.ayush.leetcodecoach.integration.RemoteProblem;
import com.ayush.leetcodecoach.integration.RemoteProblemSummary;
import com.ayush.leetcodecoach.repository.ProblemRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProblemCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ProblemCatalogService.class);

    private final ProblemRepository problemRepository;
    private final LeetCodeGraphQlClient leetCodeClient;
    private final ObjectMapper objectMapper;

    public ProblemCatalogService(
            ProblemRepository problemRepository,
            LeetCodeGraphQlClient leetCodeClient,
            ObjectMapper objectMapper) {
        this.problemRepository = problemRepository;
        this.leetCodeClient = leetCodeClient;
        this.objectMapper = objectMapper;
    }

    public Problem getProblem(String titleSlug, boolean refresh) {
        String normalizedSlug = normalizeSlug(titleSlug);
        Optional<Problem> cached = problemRepository.findBySlug(normalizedSlug);
        if (!refresh && cached.isPresent() && cached.get().statementHtml() != null) {
            return cached.get();
        }

        try {
            Problem remote = leetCodeClient.fetchProblem(normalizedSlug)
                    .map(this::fromRemoteProblem)
                    .orElseThrow(() -> new IllegalArgumentException("Problem not found: " + normalizedSlug));
            problemRepository.upsert(remote);
            return problemRepository.findBySlug(normalizedSlug).orElse(remote);
        }
        catch (LeetCodeIntegrationException ex) {
            if (cached.isPresent()) {
                log.warn("Using cached problem {} because remote sync failed: {}", normalizedSlug, ex.getMessage());
                return cached.get();
            }
            throw ex;
        }
    }

    public List<Problem> searchProblems(String keyword, String difficulty, String topic, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        String normalizedDifficulty = normalizeDifficultyFilter(difficulty);
        try {
            List<Problem> remoteProblems = leetCodeClient.searchProblems(keyword, normalizedDifficulty, topic, safeLimit)
                    .stream()
                    .map(this::fromRemoteSummary)
                    .toList();
            remoteProblems.forEach(problemRepository::upsert);
            if (!remoteProblems.isEmpty()) {
                return remoteProblems;
            }
        }
        catch (LeetCodeIntegrationException ex) {
            log.warn("LeetCode search unavailable; using SQLite catalog: {}", ex.getMessage());
        }
        return problemRepository.search(keyword, normalizedDifficulty, topic, safeLimit);
    }

    public Optional<Problem> recommendUnattempted(String difficulty, String topic) {
        String normalizedDifficulty = normalizeDifficultyFilter(difficulty);
        Optional<Problem> local = problemRepository.recommendUnattempted(normalizedDifficulty, topic);
        if (local.isPresent()) {
            return local;
        }
        searchProblems(null, normalizedDifficulty, topic, 25);
        return problemRepository.recommendUnattempted(normalizedDifficulty, topic);
    }

    public long count() {
        return problemRepository.count();
    }

    public void saveSeed(Problem problem) {
        problemRepository.upsert(problem);
    }

    private Problem fromRemoteProblem(RemoteProblem remote) {
        Double acceptanceRate = parseAcceptanceRate(remote.stats());
        return new Problem(
                null,
                remote.questionFrontendId(),
                remote.title(),
                remote.titleSlug(),
                Difficulty.from(remote.difficulty()),
                Boolean.TRUE.equals(remote.isPaidOnly()),
                remote.status(),
                acceptanceRate,
                remote.content(),
                remote.sampleTestCase(),
                remote.hints() == null ? List.of() : remote.hints(),
                remote.topicTags() == null ? List.of() : remote.topicTags().stream()
                        .map(tag -> new TopicTag(tag.name(), tag.slug()))
                        .toList(),
                remote.codeSnippets() == null ? List.of() : remote.codeSnippets().stream()
                        .map(snippet -> new CodeSnippet(snippet.lang(), snippet.langSlug(), snippet.code()))
                        .toList(),
                "LEETCODE_GRAPHQL",
                Instant.now());
    }

    private Problem fromRemoteSummary(RemoteProblemSummary remote) {
        return new Problem(
                null,
                remote.frontendQuestionId(),
                remote.title(),
                remote.titleSlug(),
                Difficulty.from(remote.difficulty()),
                Boolean.TRUE.equals(remote.paidOnly()),
                remote.status(),
                normalizeAcceptanceRate(remote.acRate()),
                null,
                null,
                List.of(),
                remote.topicTags() == null ? List.of() : remote.topicTags().stream()
                        .map(tag -> new TopicTag(tag.name(), tag.slug()))
                        .toList(),
                List.of(),
                "LEETCODE_GRAPHQL",
                Instant.now());
    }

    private Double normalizeAcceptanceRate(Double value) {
        if (value == null) {
            return null;
        }
        return value >= 0.0 && value <= 1.0 ? value * 100.0 : value;
    }

    private Double parseAcceptanceRate(String statsJson) {
        if (statsJson == null || statsJson.isBlank()) {
            return null;
        }
        try {
            var stats = objectMapper.readValue(statsJson, new TypeReference<java.util.Map<String, Object>>() { });
            Object value = stats.get("acRate");
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            if (value instanceof String text) {
                String normalized = text.trim().replace("%", "");
                return normalized.isBlank() ? null : Double.parseDouble(normalized);
            }
            return null;
        }
        catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeDifficultyFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Difficulty parsed = Difficulty.from(value);
        if (parsed == Difficulty.UNKNOWN) {
            throw new IllegalArgumentException("difficulty must be EASY, MEDIUM, or HARD");
        }
        return parsed.name();
    }

    private String normalizeSlug(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("titleSlug is required");
        }
        return value.trim().toLowerCase().replace(' ', '-');
    }
}
