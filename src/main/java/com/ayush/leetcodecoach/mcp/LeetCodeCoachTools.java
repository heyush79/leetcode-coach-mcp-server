package com.ayush.leetcodecoach.mcp;

import com.ayush.leetcodecoach.domain.Attempt;
import com.ayush.leetcodecoach.domain.CompletionSummary;
import com.ayush.leetcodecoach.domain.DueReviewList;
import com.ayush.leetcodecoach.domain.HintResponse;
import com.ayush.leetcodecoach.domain.LeetCodeAuthStatus;
import com.ayush.leetcodecoach.domain.PracticeSession;
import com.ayush.leetcodecoach.domain.PracticeSessionList;
import com.ayush.leetcodecoach.domain.Problem;
import com.ayush.leetcodecoach.domain.ProblemSearchResult;
import com.ayush.leetcodecoach.domain.ProgressStats;
import com.ayush.leetcodecoach.domain.Recommendation;
import com.ayush.leetcodecoach.domain.SessionContext;
import com.ayush.leetcodecoach.domain.SyncReport;
import com.ayush.leetcodecoach.domain.SyncStatus;
import com.ayush.leetcodecoach.domain.TopicMasteryReport;
import com.ayush.leetcodecoach.integration.LeetCodeGraphQlClient;
import com.ayush.leetcodecoach.service.PracticeService;
import com.ayush.leetcodecoach.service.ProblemCatalogService;
import com.ayush.leetcodecoach.sync.SubmissionSyncService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class LeetCodeCoachTools {

    private final ProblemCatalogService catalogService;
    private final PracticeService practiceService;
    private final LeetCodeGraphQlClient leetCodeClient;
    private final SubmissionSyncService syncService;

    public LeetCodeCoachTools(
            ProblemCatalogService catalogService,
            PracticeService practiceService,
            LeetCodeGraphQlClient leetCodeClient,
            SubmissionSyncService syncService) {
        this.catalogService = catalogService;
        this.practiceService = practiceService;
        this.leetCodeClient = leetCodeClient;
        this.syncService = syncService;
    }

    @McpTool(
            name = "search_problems",
            description = "Search LeetCode problems by keyword, difficulty, or topic. Uses live GraphQL and falls back to SQLite.",
            generateOutputSchema = true)
    public ProblemSearchResult searchProblems(
            @McpToolParam(description = "Optional title keyword", required = false) String keyword,
            @McpToolParam(description = "Optional EASY, MEDIUM, or HARD", required = false) String difficulty,
            @McpToolParam(description = "Optional topic slug such as array, graph, or dynamic-programming", required = false) String topic,
            @McpToolParam(description = "Maximum number of results, from 1 to 50", required = false) Integer limit) {
        return ProblemSearchResult.of(
                catalogService.searchProblems(keyword, difficulty, topic, limit == null ? 10 : limit));
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
            description = "Mark a practice session complete, store retrospective notes, and schedule the next "
                    + "spaced-repetition review. Recall is graded from attempts, hints used, and time taken.",
            generateOutputSchema = true)
    public CompletionSummary completePractice(
            @McpToolParam(description = "Practice session UUID", required = true) String sessionId,
            @McpToolParam(description = "What was learned and what to revise", required = false) String notes) {
        return practiceService.completePractice(sessionId, notes);
    }

    @McpTool(
            name = "get_progress_stats",
            description = "Get persisted practice totals, accepted attempts, active days, streak, reviews due now, "
                    + "and difficulty split.",
            generateOutputSchema = true)
    public ProgressStats getProgressStats() {
        return practiceService.getProgressStats();
    }

    @McpTool(
            name = "recommend_next_problem",
            description = "Recommend what to practise next: a problem due for review first, then a new problem "
                    + "in the weakest topic, then any unattempted problem. Optionally constrained by difficulty and topic.",
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
    public PracticeSessionList recentPracticeSessions(
            @McpToolParam(description = "Maximum number of sessions", required = false) Integer limit) {
        return PracticeSessionList.of(practiceService.recentSessions(limit));
    }

    @McpTool(
            name = "get_due_reviews",
            description = "List problems whose spaced-repetition review is due now, most overdue first. "
                    + "Use this to decide what the user should re-solve before attempting anything new.",
            generateOutputSchema = true)
    public DueReviewList getDueReviews(
            @McpToolParam(description = "Maximum number of reviews, from 1 to 50", required = false) Integer limit) {
        return DueReviewList.of(practiceService.dueReviews(limit));
    }

    @McpTool(
            name = "get_topic_mastery",
            description = "Report recall performance per topic, weakest first, based on review history. "
                    + "Use this to explain which concepts the user keeps forgetting.",
            generateOutputSchema = true)
    public TopicMasteryReport getTopicMastery() {
        return TopicMasteryReport.of(practiceService.topicMastery());
    }

    @McpTool(
            name = "sync_leetcode_submissions",
            description = "Pull the user's own recent submissions from leetcode.com into their practice history, "
                    + "grade each sitting, and rebuild the review schedule of every problem touched. Requires "
                    + "LEETCODE_SESSION and LEETCODE_CSRF_TOKEN. Runs in the background on a schedule; call this "
                    + "when the user has just been solving on leetcode.com and wants the coach caught up now.",
            generateOutputSchema = true)
    public SyncReport syncLeetCodeSubmissions(
            @McpToolParam(description = "Re-scan the full history instead of stopping at the first "
                    + "already-known submission. Use once to backfill older history.", required = false) Boolean full) {
        return syncService.sync(Boolean.TRUE.equals(full));
    }

    @McpTool(
            name = "get_sync_status",
            description = "Report whether submissions can be synced from leetcode.com, when they last were, and "
                    + "how many have been imported. Use this to explain why history looks empty.",
            generateOutputSchema = true)
    public SyncStatus getSyncStatus() {
        return syncService.status();
    }
}
