package com.ayush.leetcodecoach.mcp;

import com.ayush.leetcodecoach.domain.Attempt;
import com.ayush.leetcodecoach.domain.HintResponse;
import com.ayush.leetcodecoach.domain.LeetCodeAuthStatus;
import com.ayush.leetcodecoach.domain.PracticeSession;
import com.ayush.leetcodecoach.domain.Problem;
import com.ayush.leetcodecoach.domain.ProgressStats;
import com.ayush.leetcodecoach.domain.Recommendation;
import com.ayush.leetcodecoach.domain.SessionContext;
import com.ayush.leetcodecoach.integration.LeetCodeGraphQlClient;
import com.ayush.leetcodecoach.service.PracticeService;
import com.ayush.leetcodecoach.service.ProblemCatalogService;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class LeetCodeCoachTools {

    private final ProblemCatalogService catalogService;
    private final PracticeService practiceService;
    private final LeetCodeGraphQlClient leetCodeClient;

    public LeetCodeCoachTools(
            ProblemCatalogService catalogService,
            PracticeService practiceService,
            LeetCodeGraphQlClient leetCodeClient) {
        this.catalogService = catalogService;
        this.practiceService = practiceService;
        this.leetCodeClient = leetCodeClient;
    }

    @McpTool(
            name = "search_problems",
            description = "Search LeetCode problems by keyword, difficulty, or topic. Uses live GraphQL and falls back to SQLite.",
            generateOutputSchema = true)
    public List<Problem> searchProblems(
            @McpToolParam(description = "Optional title keyword", required = false) String keyword,
            @McpToolParam(description = "Optional EASY, MEDIUM, or HARD", required = false) String difficulty,
            @McpToolParam(description = "Optional topic slug such as array, graph, or dynamic-programming", required = false) String topic,
            @McpToolParam(description = "Maximum number of results, from 1 to 50", required = false) Integer limit) {
        return catalogService.searchProblems(keyword, difficulty, topic, limit == null ? 10 : limit);
    }

    @McpTool(
            name = "get_problem",
            description = "Get a problem statement, tags, hints, and starter code by title slug.",
            generateOutputSchema = true)
    public Problem getProblem(
            @McpToolParam(description = "LeetCode title slug, for example two-sum", required = true) String titleSlug,
            @McpToolParam(description = "Force a live GraphQL refresh instead of preferring SQLite", required = false) Boolean refresh) {
        return catalogService.getProblem(titleSlug, Boolean.TRUE.equals(refresh));
    }

    @McpTool(
            name = "start_practice",
            description = "Start a persisted coaching session for a problem.",
            generateOutputSchema = true)
    public PracticeSession startPractice(
            @McpToolParam(description = "LeetCode title slug", required = true) String titleSlug,
            @McpToolParam(description = "Target duration in minutes", required = false) Integer targetMinutes) {
        return practiceService.startPractice(titleSlug, targetMinutes);
    }

    @McpTool(
            name = "get_session_context",
            description = "Load the current practice session, problem, and prior attempts for coaching.",
            generateOutputSchema = true)
    public SessionContext getSessionContext(
            @McpToolParam(description = "Practice session UUID", required = true) String sessionId) {
        return practiceService.getSessionContext(sessionId);
    }

    @McpTool(
            name = "get_hint",
            description = "Return progressive hints without returning a complete solution. Level must be 1, 2, or 3.",
            generateOutputSchema = true)
    public HintResponse getHint(
            @McpToolParam(description = "Practice session UUID", required = true) String sessionId,
            @McpToolParam(description = "Hint level from 1 to 3", required = false) Integer level) {
        return practiceService.getHint(sessionId, level);
    }

    @McpTool(
            name = "record_attempt",
            description = "Persist a coding attempt and its self-reported or externally evaluated result.",
            generateOutputSchema = true)
    public Attempt recordAttempt(
            @McpToolParam(description = "Practice session UUID", required = true) String sessionId,
            @McpToolParam(description = "Programming language", required = true) String language,
            @McpToolParam(description = "Submitted source code", required = true) String code,
            @McpToolParam(description = "Verdict such as ACCEPTED, WRONG_ANSWER, TLE, or UNASSESSED", required = false) String verdict,
            @McpToolParam(description = "Runtime in milliseconds", required = false) Long runtimeMs,
            @McpToolParam(description = "Memory in kilobytes", required = false) Long memoryKb,
            @McpToolParam(description = "Claimed time complexity", required = false) String timeComplexity,
            @McpToolParam(description = "Claimed space complexity", required = false) String spaceComplexity,
            @McpToolParam(description = "Reflection or review notes", required = false) String notes) {
        return practiceService.recordAttempt(
                sessionId, language, code, verdict, runtimeMs, memoryKb,
                timeComplexity, spaceComplexity, notes);
    }

    @McpTool(
            name = "complete_practice",
            description = "Mark a practice session complete and store retrospective notes.",
            generateOutputSchema = true)
    public PracticeSession completePractice(
            @McpToolParam(description = "Practice session UUID", required = true) String sessionId,
            @McpToolParam(description = "What was learned and what to revise", required = false) String notes) {
        return practiceService.completePractice(sessionId, notes);
    }

    @McpTool(
            name = "get_progress_stats",
            description = "Get persisted practice totals, accepted attempts, active days, streak, and difficulty split.",
            generateOutputSchema = true)
    public ProgressStats getProgressStats() {
        return practiceService.getProgressStats();
    }

    @McpTool(
            name = "recommend_next_problem",
            description = "Recommend an unattempted problem, optionally constrained by difficulty and topic.",
            generateOutputSchema = true)
    public Recommendation recommendNextProblem(
            @McpToolParam(description = "Optional EASY, MEDIUM, or HARD", required = false) String difficulty,
            @McpToolParam(description = "Optional topic slug", required = false) String topic) {
        return practiceService.recommendNext(difficulty, topic);
    }

    @McpTool(
            name = "verify_leetcode_auth",
            description = "Verify whether the configured LeetCode session cookie is accepted by the GraphQL endpoint.",
            generateOutputSchema = true)
    public LeetCodeAuthStatus verifyLeetCodeAuth() {
        return leetCodeClient.verifyAuthentication();
    }

    @McpTool(
            name = "recent_practice_sessions",
            description = "List recently started practice sessions.",
            generateOutputSchema = true)
    public List<PracticeSession> recentPracticeSessions(
            @McpToolParam(description = "Maximum number of sessions", required = false) Integer limit) {
        return practiceService.recentSessions(limit);
    }
}
