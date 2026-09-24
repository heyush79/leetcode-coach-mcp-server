package com.ayush.leetcodecoach.sync;

import com.ayush.leetcodecoach.config.LeetCodeProperties;
import com.ayush.leetcodecoach.domain.Attempt;
import com.ayush.leetcodecoach.domain.PracticeSession;
import com.ayush.leetcodecoach.integration.RemoteSubmission;
import com.ayush.leetcodecoach.repository.PracticeRepository;
import com.ayush.leetcodecoach.service.ProblemCatalogService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns one leetcode.com submission into a stored attempt, inside a practice session.
 *
 * <p>LeetCode has no notion of a session; it has a flat stream of submissions. A session is
 * inferred here, in order of preference:
 *
 * <ol>
 *   <li>An assistant-driven session for the same problem that was open at the time, or had
 *       completed shortly before. The user was coached through this sitting, so the real verdict
 *       belongs with the hints they used.</li>
 *   <li>A previously synced session that ended less than the configured gap before. The same
 *       sitting, continued.</li>
 *   <li>Otherwise a new synced session, one submission long. Later submissions may extend it.</li>
 * </ol>
 *
 * <p>Each import runs in its own transaction so that a failure part-way through a sync leaves the
 * earlier submissions stored, and a later sync picks up where it stopped.
 */
@Service
public class SubmissionImporter {

    private static final Logger log = LoggerFactory.getLogger(SubmissionImporter.class);

    /** What importing one submission did, so the sync can total it up. */
    public record Outcome(String titleSlug, boolean sessionCreated, boolean sessionUpdated, boolean problemAdded) {
    }

    private final PracticeRepository practiceRepository;
    private final ProblemCatalogService catalogService;
    private final LeetCodeProperties properties;

    public SubmissionImporter(
            PracticeRepository practiceRepository,
            ProblemCatalogService catalogService,
            LeetCodeProperties properties) {
        this.practiceRepository = practiceRepository;
        this.catalogService = catalogService;
        this.properties = properties;
    }

    /** Stores the submission, or returns empty when it is pending, malformed, or already stored. */
    @Transactional
    public Optional<Outcome> importSubmission(RemoteSubmission remote) {
        if (remote.id() == null || remote.id().isBlank()
                || remote.titleSlug() == null || remote.titleSlug().isBlank()) {
            log.warn("Skipping a LeetCode submission without an id or slug: {}", remote);
            return Optional.empty();
        }
        if (SubmissionParser.isPending(remote)) {
            return Optional.empty();
        }
        Instant submittedAt = SubmissionParser.submittedAt(remote);
        if (submittedAt == null) {
            log.warn("Skipping LeetCode submission {} with unreadable timestamp '{}'", remote.id(), remote.timestamp());
            return Optional.empty();
        }
        if (practiceRepository.attemptExistsForSubmission(remote.id())) {
            return Optional.empty();
        }

        String titleSlug = remote.titleSlug().trim().toLowerCase();
        boolean problemAdded = ensureProblem(titleSlug, remote.title());

        List<PracticeSession> existing = practiceRepository.findSessionsForProblem(titleSlug);
        PracticeSession target;
        boolean created = false;

        Optional<PracticeSession> coached = latest(existing.stream()
                .filter(PracticeSession::isManual)
                .filter(session -> coversWithSlack(session, submittedAt))
                .toList());
        Optional<PracticeSession> continued = coached.isPresent() ? Optional.empty() : latest(existing.stream()
                .filter(session -> PracticeSession.SOURCE_LEETCODE.equals(session.source()))
                .filter(session -> withinGap(session, submittedAt))
                .toList());

        if (coached.isPresent()) {
            target = coached.get();
        }
        else if (continued.isPresent()) {
            target = continued.get();
            Instant start = earlier(target.startedAt(), submittedAt);
            Instant end = later(target.completedAt(), submittedAt);
            practiceRepository.extendSession(target.id(), start, end);
        }
        else {
            target = new PracticeSession(
                    UUID.randomUUID().toString(),
                    titleSlug,
                    "COMPLETED",
                    submittedAt,
                    submittedAt,
                    null,
                    null,
                    0,
                    PracticeSession.SOURCE_LEETCODE);
            practiceRepository.insertSession(target);
            created = true;
        }

        practiceRepository.insertAttempt(new Attempt(
                UUID.randomUUID().toString(),
                target.id(),
                remote.lang() == null || remote.lang().isBlank() ? "unknown" : remote.lang().trim(),
                "",
                SubmissionParser.verdict(remote.statusDisplay()),
                SubmissionParser.runtimeMs(remote.runtime()),
                SubmissionParser.memoryKb(remote.memory()),
                null,
                null,
                null,
                submittedAt,
                Attempt.SOURCE_LEETCODE,
                remote.id().trim()));

        return Optional.of(new Outcome(titleSlug, created, !created, problemAdded));
    }

    /**
     * Makes sure the problem exists so the session's foreign key holds. Tries to fetch the full
     * detail, and falls back to a stub carrying only the title when LeetCode cannot be reached, so
     * an outage during sync never loses a submission.
     */
    private boolean ensureProblem(String titleSlug, String title) {
        if (catalogService.findCached(titleSlug).isPresent()) {
            return false;
        }
        try {
            catalogService.getProblem(titleSlug, false);
        }
        catch (RuntimeException ex) {
            log.warn("Could not fetch detail for '{}' during sync; storing a stub: {}", titleSlug, ex.getMessage());
            catalogService.registerStub(titleSlug, title);
        }
        return true;
    }

    /** An assistant session that was open at the time, or had ended less than the slack before. */
    private boolean coversWithSlack(PracticeSession session, Instant at) {
        if (at.isBefore(session.startedAt())) {
            return false;
        }
        if (session.completedAt() == null) {
            return true;
        }
        Duration slack = Duration.ofMinutes(properties.getSync().getManualSessionSlackMinutes());
        return !at.isAfter(session.completedAt().plus(slack));
    }

    /** A synced session whose span is within the gap of this submission, on either side. */
    private boolean withinGap(PracticeSession session, Instant at) {
        Duration gap = Duration.ofMinutes(properties.getSync().getSessionGapMinutes());
        Instant end = session.completedAt() == null ? session.startedAt() : session.completedAt();
        return !at.isBefore(session.startedAt().minus(gap)) && !at.isAfter(end.plus(gap));
    }

    private Optional<PracticeSession> latest(List<PracticeSession> sessions) {
        return sessions.isEmpty() ? Optional.empty() : Optional.of(sessions.get(sessions.size() - 1));
    }

    private Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private Instant later(Instant a, Instant b) {
        return a == null || b.isAfter(a) ? b : a;
    }
}
