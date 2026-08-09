package com.ayush.leetcodecoach.service;

import com.ayush.leetcodecoach.domain.Attempt;
import com.ayush.leetcodecoach.domain.HintResponse;
import com.ayush.leetcodecoach.domain.PracticeSession;
import com.ayush.leetcodecoach.domain.Problem;
import com.ayush.leetcodecoach.domain.ProgressStats;
import com.ayush.leetcodecoach.domain.Recommendation;
import com.ayush.leetcodecoach.domain.SessionContext;
import com.ayush.leetcodecoach.repository.PracticeRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PracticeService {

    private final ProblemCatalogService catalogService;
    private final PracticeRepository practiceRepository;

    public PracticeService(ProblemCatalogService catalogService, PracticeRepository practiceRepository) {
        this.catalogService = catalogService;
        this.practiceRepository = practiceRepository;
    }

    @Transactional
    public PracticeSession startPractice(String titleSlug, Integer targetMinutes) {
        Problem problem = catalogService.getProblem(titleSlug, false);
        int safeTarget = targetMinutes == null ? 30 : Math.max(5, Math.min(targetMinutes, 240));
        PracticeSession session = new PracticeSession(
                UUID.randomUUID().toString(),
                problem.titleSlug(),
                "ACTIVE",
                Instant.now(),
                null,
                safeTarget,
                null);
        practiceRepository.insertSession(session);
        return session;
    }

    public SessionContext getSessionContext(String sessionId) {
        PracticeSession session = requireSession(sessionId);
        Problem problem = catalogService.getProblem(session.titleSlug(), false);
        return new SessionContext(session, problem, practiceRepository.findAttempts(sessionId));
    }

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
                Instant.now());
        practiceRepository.insertAttempt(attempt);
        return attempt;
    }

    @Transactional
    public PracticeSession completePractice(String sessionId, String notes) {
        PracticeSession session = requireSession(sessionId);
        if ("COMPLETED".equals(session.status())) {
            return session;
        }
        practiceRepository.completeSession(sessionId, notes, Instant.now());
        return requireSession(sessionId);
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
                byDifficulty);
    }

    public Recommendation recommendNext(String difficulty, String topic) {
        Problem problem = catalogService.recommendUnattempted(difficulty, topic)
                .orElseThrow(() -> new IllegalStateException("No matching unattempted problem is available"));
        String reason = "Selected an unattempted " + problem.difficulty().name().toLowerCase()
                + " problem"
                + (topic == null || topic.isBlank() ? " from the local/remote catalog." : " matching topic '" + topic + "'.");
        return new Recommendation(problem, reason);
    }

    public List<PracticeSession> recentSessions(Integer limit) {
        return practiceRepository.recentSessions(limit == null ? 10 : limit);
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
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
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
