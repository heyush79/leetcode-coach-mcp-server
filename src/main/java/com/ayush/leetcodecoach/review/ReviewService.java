package com.ayush.leetcodecoach.review;

import com.ayush.leetcodecoach.domain.Attempt;
import com.ayush.leetcodecoach.domain.DueReview;
import com.ayush.leetcodecoach.domain.PracticeSession;
import com.ayush.leetcodecoach.domain.Problem;
import com.ayush.leetcodecoach.domain.RecallSignal;
import com.ayush.leetcodecoach.domain.ReviewOutcome;
import com.ayush.leetcodecoach.domain.ReviewSchedule;
import com.ayush.leetcodecoach.domain.TopicMastery;
import com.ayush.leetcodecoach.domain.TopicTag;
import com.ayush.leetcodecoach.repository.ProblemRepository;
import com.ayush.leetcodecoach.repository.ReviewRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides when each problem should be practised again.
 *
 * <p>Completing a session produces a measured recall grade ({@link RecallGrader}), which advances
 * that problem's SM-2 state ({@link SpacedRepetitionScheduler}). Everything else here reads from
 * that state: what is due, and which topics keep lapsing.
 */
@Service
public class ReviewService {

    /** Below this, a topic is reported as weak. */
    private static final double WEAK_MASTERY_THRESHOLD = 0.6;

    /** One review says nothing about a topic; require a second before judging it. */
    private static final int MINIMUM_REVIEWS_FOR_WEAKNESS = 2;

    private final ReviewRepository reviewRepository;
    private final ProblemRepository problemRepository;
    private final SpacedRepetitionScheduler scheduler;
    private final RecallGrader grader;

    public ReviewService(
            ReviewRepository reviewRepository,
            ProblemRepository problemRepository,
            SpacedRepetitionScheduler scheduler,
            RecallGrader grader) {
        this.reviewRepository = reviewRepository;
        this.problemRepository = problemRepository;
        this.scheduler = scheduler;
        this.grader = grader;
    }

    /** Grades a finished session and advances the problem's review schedule. */
    @Transactional
    public ReviewOutcome recordCompletedSession(PracticeSession session, List<Attempt> attempts, Instant completedAt) {
        RecallSignal signal = toSignal(session, attempts, completedAt);
        int grade = grader.grade(signal);

        ReviewSchedule current = reviewRepository.find(session.titleSlug())
                .orElseGet(() -> scheduler.initial(session.titleSlug(), completedAt));
        ReviewSchedule updated = scheduler.next(current, grade, completedAt);
        reviewRepository.upsert(updated);

        return new ReviewOutcome(
                session.titleSlug(),
                grade,
                grader.explain(signal, grade),
                updated,
                updated.intervalDays());
    }

    /** Problems whose review is due now, most overdue first. */
    public List<DueReview> dueReviews(Instant now, int limit) {
        List<DueReview> due = new ArrayList<>();
        for (ReviewSchedule schedule : reviewRepository.findDue(now, limit)) {
            Optional<Problem> problem = problemRepository.findBySlug(schedule.titleSlug());
            if (problem.isEmpty()) {
                continue;
            }
            long daysOverdue = Math.max(0, Duration.between(schedule.dueAt(), now).toDays());
            due.add(new DueReview(
                    schedule.titleSlug(),
                    problem.get().title(),
                    problem.get().difficulty(),
                    schedule,
                    daysOverdue,
                    describeDue(schedule, daysOverdue)));
        }
        return due;
    }

    public long dueCount(Instant now) {
        return reviewRepository.countDue(now);
    }

    /**
     * Recall performance per topic tag.
     *
     * <p>Aggregated in Java rather than SQL because topic tags live in a JSON column: SQLite would
     * need a json_each join over a text field that exists as a cache, not as a taxonomy.
     */
    public List<TopicMastery> topicMastery() {
        Map<String, TopicAccumulator> byTopic = new LinkedHashMap<>();

        for (ReviewSchedule schedule : reviewRepository.findAll()) {
            Optional<Problem> problem = problemRepository.findBySlug(schedule.titleSlug());
            if (problem.isEmpty()) {
                continue;
            }
            for (TopicTag tag : problem.get().topicTags()) {
                byTopic.computeIfAbsent(tag.slug(), slug -> new TopicAccumulator(tag.name(), slug))
                        .add(schedule);
            }
        }

        return byTopic.values().stream()
                .map(TopicAccumulator::toMastery)
                .sorted(Comparator.comparingDouble(TopicMastery::masteryScore))
                .toList();
    }

    /** The weakest topics with enough history to be meaningful. */
    public List<TopicMastery> weakTopics() {
        return topicMastery().stream()
                .filter(topic -> topic.problemsReviewed() >= MINIMUM_REVIEWS_FOR_WEAKNESS)
                .filter(topic -> topic.masteryScore() < WEAK_MASTERY_THRESHOLD)
                .toList();
    }

    private RecallSignal toSignal(PracticeSession session, List<Attempt> attempts, Instant completedAt) {
        int attemptsBeforeAccepted = 0;
        boolean accepted = false;
        for (Attempt attempt : attempts) {
            if ("ACCEPTED".equalsIgnoreCase(attempt.verdict())) {
                accepted = true;
                break;
            }
            attemptsBeforeAccepted++;
        }

        return new RecallSignal(
                attempts.size(),
                accepted ? attemptsBeforeAccepted : 0,
                accepted,
                session.maxHintLevel(),
                Duration.between(session.startedAt(), completedAt).toMinutes(),
                session.targetMinutes() == null ? 0 : session.targetMinutes());
    }

    private String describeDue(ReviewSchedule schedule, long daysOverdue) {
        if (schedule.repetitions() == 0) {
            return "Not yet recalled successfully; scheduled to retry.";
        }
        String base = "Reviewed " + schedule.repetitions() + " time(s), interval "
                + schedule.intervalDays() + " day(s)";
        if (schedule.lapses() > 0) {
            base += ", forgotten " + schedule.lapses() + " time(s)";
        }
        return base + (daysOverdue > 0 ? ", overdue by " + daysOverdue + " day(s)." : ", due today.");
    }

    /** Running totals for one topic while scanning review state. */
    private static final class TopicAccumulator {

        private final String name;
        private final String slug;
        private int problems;
        private int gradeTotal;
        private int lapses;

        private TopicAccumulator(String name, String slug) {
            this.name = name;
            this.slug = slug;
        }

        private void add(ReviewSchedule schedule) {
            problems++;
            gradeTotal += schedule.lastGrade();
            lapses += schedule.lapses();
        }

        private TopicMastery toMastery() {
            double averageGrade = problems == 0 ? 0 : (double) gradeTotal / problems;

            // Average recall quality, discounted by how often this topic has been forgotten.
            double lapsePenalty = problems == 0 ? 0 : Math.min(0.5, lapses / (2.0 * problems));
            double mastery = round((averageGrade / 5.0) * (1 - lapsePenalty));

            return new TopicMastery(name, slug, problems, round(averageGrade), lapses, mastery, label(mastery));
        }

        private String label(double mastery) {
            if (mastery >= 0.8) {
                return "STRONG";
            }
            if (mastery >= WEAK_MASTERY_THRESHOLD) {
                return "STEADY";
            }
            if (mastery >= 0.35) {
                return "SHAKY";
            }
            return "WEAK";
        }

        private double round(double value) {
            return Math.round(value * 100.0) / 100.0;
        }
    }
}
