package com.ayush.leetcodecoach.review;

import com.ayush.leetcodecoach.domain.ReviewSchedule;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * SM-2 scheduling, adapted for coding practice.
 *
 * <p>The algorithm keeps a per-problem easiness factor and grows the gap between reviews while
 * recall succeeds. A grade below {@link #PASSING_GRADE} is a lapse: the repetition count resets and
 * the problem comes back tomorrow, because a problem you just failed is not worth deferring.
 *
 * <p>Deliberately pure and stateless. Everything it needs arrives as arguments, so the interval
 * arithmetic can be tested without a database or a clock.
 */
@Component
public class SpacedRepetitionScheduler {

    /** SM-2's floor for the easiness factor. Below this, intervals would barely grow. */
    public static final double MINIMUM_EASINESS = 1.3;

    /** SM-2's starting easiness for an unseen item. */
    public static final double INITIAL_EASINESS = 2.5;

    /** The lowest grade still counted as successful recall. */
    public static final int PASSING_GRADE = 3;

    public static final int FIRST_INTERVAL_DAYS = 1;
    public static final int SECOND_INTERVAL_DAYS = 6;

    /**
     * Intervals are capped at a year. Uncapped SM-2 eventually schedules reviews further out than
     * anyone plans an interview, which is the same as dropping the problem.
     */
    public static final int MAXIMUM_INTERVAL_DAYS = 365;

    /** The state of a problem that has never been reviewed. */
    public ReviewSchedule initial(String titleSlug, Instant now) {
        return new ReviewSchedule(titleSlug, INITIAL_EASINESS, 0, 0, 0, 0, null, now);
    }

    /**
     * Applies one recall grade and returns the next schedule.
     *
     * @param current the existing schedule, or the result of {@link #initial} for a new problem
     * @param grade recall quality from 0 (blank) to 5 (effortless)
     */
    public ReviewSchedule next(ReviewSchedule current, int grade, Instant reviewedAt) {
        int boundedGrade = Math.max(0, Math.min(grade, 5));
        double easiness = nextEasiness(current.easinessFactor(), boundedGrade);

        int repetitions;
        int intervalDays;
        int lapses = current.lapses();

        if (boundedGrade < PASSING_GRADE) {
            // A lapse only counts against a problem that had actually been learned.
            if (current.repetitions() > 0) {
                lapses++;
            }
            repetitions = 0;
            intervalDays = FIRST_INTERVAL_DAYS;
        }
        else {
            repetitions = current.repetitions() + 1;
            intervalDays = switch (repetitions) {
                case 1 -> FIRST_INTERVAL_DAYS;
                case 2 -> SECOND_INTERVAL_DAYS;
                default -> (int) Math.round(current.intervalDays() * easiness);
            };
            intervalDays = Math.max(FIRST_INTERVAL_DAYS, Math.min(intervalDays, MAXIMUM_INTERVAL_DAYS));
        }

        return new ReviewSchedule(
                current.titleSlug(),
                easiness,
                intervalDays,
                repetitions,
                lapses,
                boundedGrade,
                reviewedAt,
                dueDate(reviewedAt, intervalDays));
    }

    /**
     * Due dates land at the start of a UTC day rather than at the exact hour of the last review.
     *
     * <p>Otherwise a problem reviewed at 21:00 would not come due until 21:00 on the target day, and
     * anyone practising earlier in the evening would keep missing their own reviews.
     */
    private Instant dueDate(Instant reviewedAt, int intervalDays) {
        return reviewedAt.truncatedTo(ChronoUnit.DAYS).plus(Duration.ofDays(intervalDays));
    }

    /**
     * SM-2's easiness update. A grade of 4 leaves easiness unchanged; 5 raises it and anything
     * lower reduces it, increasingly steeply.
     */
    private double nextEasiness(double current, int grade) {
        double delta = 0.1 - (5 - grade) * (0.08 + (5 - grade) * 0.02);
        return Math.max(MINIMUM_EASINESS, current + delta);
    }
}
