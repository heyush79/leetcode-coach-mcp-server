package com.ayush.leetcodecoach.service;

import com.ayush.leetcodecoach.domain.Attempt;
import com.ayush.leetcodecoach.domain.CompletionSummary;
import com.ayush.leetcodecoach.domain.DueReview;
import com.ayush.leetcodecoach.domain.HintResponse;
import com.ayush.leetcodecoach.domain.PracticeSession;
import com.ayush.leetcodecoach.domain.Problem;
import com.ayush.leetcodecoach.domain.ProgressStats;
import com.ayush.leetcodecoach.domain.Recommendation;
import com.ayush.leetcodecoach.domain.SessionContext;
import com.ayush.leetcodecoach.domain.TopicMastery;
import com.ayush.leetcodecoach.repository.PracticeRepository;
import com.ayush.leetcodecoach.review.ReviewService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PracticeService {

    private final ProblemCatalogService catalogService;
    private final PracticeRepository practiceRepository;
    private final ReviewService reviewService;
    private final Clock clock;

    public PracticeService(
            ProblemCatalogService catalogService,
            PracticeRepository practiceRepository,
            ReviewService reviewService,
            Clock clock) {
        this.catalogService = catalogService;
        this.practiceRepository = practiceRepository;
        this.reviewService = reviewService;
        this.clock = clock;
    }

    @Transactional
    public PracticeSession startPractice(String titleSlug, Integer targetMinutes) {
        Problem problem = catalogService.getProblem(titleSlug, false);
        int safeTarget = targetMinutes == null ? 30 : Math.max(5, Math.min(targetMinutes, 240));
        PracticeSession session = new PracticeSession(
                UUID.randomUUID().toString(),
                problem.titleSlug(),
                "ACTIVE",
                Instant.now(clock),
                null,
                safeTarget,
                null,
                0);
        practiceRepository.insertSession(session);
        return session;
    }

    public SessionContext getSessionContext(String sessionId) {
        PracticeSession session = requireSession(sessionId);
        Problem problem = catalogService.getProblem(session.titleSlug(), false);
        return new SessionContext(session, problem, practiceRepository.findAttempts(sessionId));
    }

    /**
     * Returns hints up to the requested level and remembers how deep the user went, because the
     * amount of help needed is what later grades recall.
     */
    @Transactional
    public HintResponse getHint(String sessionId, Integer requestedLevel) {
        SessionContext context = getSessionContext(sessionId);
        int level = requestedLevel == null ? 1 : Math.max(1, Math.min(requestedLevel, 3));
        List<String> sourceHints = context.problem().hints() == null ? List.of() : context.problem().hints();
        List<String> hints = new ArrayList<>();

        if (!sourceHints.isEmpty()) {
            hints.addAll(sourceHints.subList(0, Math.min(level, sourceHints.size())));
        }
        if (hints.size() < level) {
            hints.addAll(genericHints(context.problem(), level - hints.size()));
        }

        practiceRepository.recordHintLevel(sessionId, level);

        return new HintResponse(
                sessionId,
                level,
                hints,
                "Hints deliberately avoid complete code. Ask the agent to challenge your reasoning before revealing another level.");
    }

    @Transactional
    public Attempt recordAttempt(
            String sessionId,
            String language,
            String code,
            String verdict,
            Long runtimeMs,
            Long memoryKb,
            String timeComplexity,
            String spaceComplexity,
            String notes) {
        PracticeSession session = requireSession(sessionId);
        if (!"ACTIVE".equals(session.status())) {
            throw new IllegalStateException("Cannot add attempts to a completed practice session");
        }
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("language is required");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code is required");
        }
        if (runtimeMs != null && runtimeMs < 0) {
            throw new IllegalArgumentException("runtimeMs cannot be negative");
        }
        if (memoryKb != null && memoryKb < 0) {
            throw new IllegalArgumentException("memoryKb cannot be negative");
        }
        Attempt attempt = new Attempt(
                UUID.randomUUID().toString(),
                sessionId,
                language.trim(),
                code,
                blankToDefault(verdict, "UNASSESSED").toUpperCase(),
                runtimeMs,
                memoryKb,
                timeComplexity,
                spaceComplexity,
                notes,
                Instant.now(clock));
        practiceRepository.insertAttempt(attempt);
        return attempt;
    }

    /**
     * Completes a session and schedules the next review of that problem.
     *
     * <p>Completing an already-completed session returns the stored state without re-grading, so a
     * repeated call cannot inflate the schedule.
     */
    @Transactional
    public CompletionSummary completePractice(String sessionId, String notes) {
        PracticeSession session = requireSession(sessionId);
        if ("COMPLETED".equals(session.status())) {
            return new CompletionSummary(session, null);
        }

        Instant completedAt = Instant.now(clock);
        practiceRepository.completeSession(sessionId, notes, completedAt);
        PracticeSession completed = requireSession(sessionId);

        return new CompletionSummary(
                completed,
                reviewService.recordCompletedSession(
                        completed, practiceRepository.findAttempts(sessionId), completedAt));
    }

    public ProgressStats getProgressStats() {
        List<LocalDate> dates = practiceRepository.completionDates();
        Map<String, Long> byDifficulty = new LinkedHashMap<>();
        byDifficulty.put("EASY", 0L);
        byDifficulty.put("MEDIUM", 0L);
        byDifficulty.put("HARD", 0L);
        byDifficulty.putAll(practiceRepository.completedByDifficulty());
        return new ProgressStats(
                practiceRepository.completedSessionCount(),
                practiceRepository.acceptedAttemptCount(),
                practiceRepository.totalAttemptCount(),
                dates.size(),
                calculateCurrentStreak(dates),
                reviewService.dueCount(Instant.now(clock)),
                byDifficulty);
    }

    /**
     * Recommends what to practise next, in priority order: a problem that is due for review, then
     * a new problem in the weakest topic, then any unattempted problem.
     *
     * <p>Reviews come first because re-solving a problem you are about to forget is worth more than
     * adding a new one you will also forget.
     */
    public Recommendation recommendNext(String difficulty, String topic) {
        Optional<Recommendation> dueReview = recommendDueReview(difficulty, topic);
        if (dueReview.isPresent()) {
            return dueReview.get();
        }

        String effectiveTopic = topic;
        String weaknessNote = "";
        if (topic == null || topic.isBlank()) {
            Optional<TopicMastery> weakest = reviewService.weakTopics().stream().findFirst();
            if (weakest.isPresent()) {
                effectiveTopic = weakest.get().topicSlug();
                weaknessNote = " Chosen because '" + weakest.get().topic() + "' is your weakest topic (mastery "
                        + weakest.get().masteryScore() + ").";
            }
        }

        Optional<Problem> match = catalogService.recommendUnattempted(difficulty, effectiveTopic);
        if (match.isEmpty() && effectiveTopic != null && !effectiveTopic.equals(topic)) {
            // The weak topic had nothing unattempted left; fall back to the caller's own filters.
            match = catalogService.recommendUnattempted(difficulty, topic);
            weaknessNote = "";
        }

        Problem problem = match.orElseThrow(
                () -> new IllegalStateException("No matching unattempted problem is available"));

        String reason = "Selected an unattempted " + problem.difficulty().name().toLowerCase() + " problem"
                + (topic == null || topic.isBlank() ? " from the local/remote catalog." : " matching topic '" + topic + "'.")
                + weaknessNote;
        return new Recommendation(problem, reason);
    }

    public List<DueReview> dueReviews(Integer limit) {
        return reviewService.dueReviews(Instant.now(clock), limit == null ? 10 : limit);
    }

    public List<TopicMastery> topicMastery() {
        return reviewService.topicMastery();
    }

    public List<PracticeSession> recentSessions(Integer limit) {
        return practiceRepository.recentSessions(limit == null ? 10 : limit);
    }

    private Optional<Recommendation> recommendDueReview(String difficulty, String topic) {
        for (DueReview due : reviewService.dueReviews(Instant.now(clock), 50)) {
            if (difficulty != null && !difficulty.isBlank()
                    && !due.difficulty().name().equalsIgnoreCase(difficulty.trim())) {
                continue;
            }
            Optional<Problem> problem = catalogService.findCached(due.titleSlug());
            if (problem.isEmpty()) {
                continue;
            }
            if (topic != null && !topic.isBlank() && problem.get().topicTags().stream()
                    .noneMatch(tag -> tag.slug().equalsIgnoreCase(topic.trim()))) {
                continue;
            }
            return Optional.of(new Recommendation(
                    problem.get(),
                    "Due for spaced-repetition review. " + due.reason()));
        }
        return Optional.empty();
    }

    private PracticeSession requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        return practiceRepository.findSession(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Practice session not found: " + sessionId));
    }

    private List<String> genericHints(Problem problem, int count) {
        List<String> candidates = new ArrayList<>();
        String topics = problem.topicTags() == null || problem.topicTags().isEmpty()
                ? "the constraints and data flow"
                : problem.topicTags().stream().map(tag -> tag.name()).limit(3).reduce((a, b) -> a + ", " + b).orElse("the constraints");
        candidates.add("Write down the input constraints and decide which brute-force operation is too expensive.");
        candidates.add("The tagged concepts are " + topics + ". Identify what state must be remembered while scanning or exploring.");
        candidates.add("State an invariant for your loop, recursion, or data structure before writing code, then test it on the smallest edge case.");
        return candidates.subList(0, Math.min(count, candidates.size()));
    }

    private long calculateCurrentStreak(List<LocalDate> completionDates) {
        if (completionDates.isEmpty()) {
            return 0;
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate expected = completionDates.get(0).equals(today) ? today : today.minusDays(1);
        long streak = 0;
        for (LocalDate date : completionDates) {
            if (date.equals(expected)) {
                streak++;
                expected = expected.minusDays(1);
            }
            else if (date.isBefore(expected)) {
                break;
            }
        }
        return streak;
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
