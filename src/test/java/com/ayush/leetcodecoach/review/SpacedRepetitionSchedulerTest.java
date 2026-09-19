package com.ayush.leetcodecoach.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.ayush.leetcodecoach.domain.ReviewSchedule;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SpacedRepetitionSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T09:00:00Z");

    private final SpacedRepetitionScheduler scheduler = new SpacedRepetitionScheduler();

    @Test
    void firstSuccessfulRecallSchedulesTomorrow() {
        ReviewSchedule result = scheduler.next(scheduler.initial("two-sum", NOW), 5, NOW);

        assertThat(result.repetitions()).isEqualTo(1);
        assertThat(result.intervalDays()).isEqualTo(1);
        assertThat(result.dueAt()).isEqualTo(Instant.parse("2026-01-02T00:00:00Z"));
        assertThat(result.lastReviewedAt()).isEqualTo(NOW);
    }

    @Test
    void secondSuccessfulRecallJumpsToSixDays() {
        ReviewSchedule after = scheduler.next(scheduler.initial("two-sum", NOW), 5, NOW);

        ReviewSchedule result = scheduler.next(after, 5, NOW);

        assertThat(result.repetitions()).isEqualTo(2);
        assertThat(result.intervalDays()).isEqualTo(6);
    }

    @Test
    void laterIntervalsMultiplyByTheUpdatedEasinessFactor() {
        ReviewSchedule state = scheduler.initial("two-sum", NOW);
        state = scheduler.next(state, 5, NOW);
        state = scheduler.next(state, 5, NOW);

        // Easiness after three perfect recalls is 2.5 + 0.1 * 3 = 2.8, applied to the 6-day interval.
        ReviewSchedule result = scheduler.next(state, 5, NOW);

        assertThat(result.easinessFactor()).isCloseTo(2.8, within(1e-9));
        assertThat(result.intervalDays()).isEqualTo(17);
        assertThat(result.repetitions()).isEqualTo(3);
    }

    @Test
    void gradeOfFourLeavesEasinessUnchanged() {
        ReviewSchedule result = scheduler.next(scheduler.initial("two-sum", NOW), 4, NOW);

        assertThat(result.easinessFactor()).isCloseTo(SpacedRepetitionScheduler.INITIAL_EASINESS, within(1e-9));
    }

    @Test
    void failedRecallResetsRepetitionsAndBringsTheProblemBackTomorrow() {
        ReviewSchedule learned = scheduler.next(
                scheduler.next(scheduler.initial("coin-change", NOW), 5, NOW), 5, NOW);
        assertThat(learned.intervalDays()).isEqualTo(6);

        ReviewSchedule result = scheduler.next(learned, 2, NOW);

        assertThat(result.repetitions()).isZero();
        assertThat(result.intervalDays()).isEqualTo(1);
        assertThat(result.lapses()).isEqualTo(1);
        assertThat(result.dueAt()).isEqualTo(Instant.parse("2026-01-02T00:00:00Z"));
    }

    @Test
    void failingAProblemThatWasNeverLearnedIsNotCountedAsALapse() {
        ReviewSchedule result = scheduler.next(scheduler.initial("lru-cache", NOW), 1, NOW);

        assertThat(result.repetitions()).isZero();
        assertThat(result.lapses()).isZero();
    }

    @Test
    void easinessNeverFallsBelowTheFloor() {
        ReviewSchedule state = scheduler.initial("hard-problem", NOW);
        for (int i = 0; i < 10; i++) {
            state = scheduler.next(state, 0, NOW);
        }

        assertThat(state.easinessFactor()).isEqualTo(SpacedRepetitionScheduler.MINIMUM_EASINESS);
    }

    @Test
    void intervalsAreCappedAtOneYear() {
        ReviewSchedule nearlyCapped = new ReviewSchedule(
                "two-sum", 2.5, 300, 5, 0, 5, NOW, NOW);

        ReviewSchedule result = scheduler.next(nearlyCapped, 5, NOW);

        assertThat(result.intervalDays()).isEqualTo(SpacedRepetitionScheduler.MAXIMUM_INTERVAL_DAYS);
    }

    @Test
    void gradesAreClampedToTheValidRange() {
        ReviewSchedule tooHigh = scheduler.next(scheduler.initial("two-sum", NOW), 99, NOW);
        ReviewSchedule tooLow = scheduler.next(scheduler.initial("two-sum", NOW), -5, NOW);

        assertThat(tooHigh.lastGrade()).isEqualTo(5);
        assertThat(tooLow.lastGrade()).isZero();
    }

    @Test
    void dueDatesLandAtTheStartOfTheDaySoAnEarlierPracticeSessionStillSeesThem() {
        Instant lateEvening = Instant.parse("2026-01-01T21:30:00Z");

        ReviewSchedule result = scheduler.next(scheduler.initial("two-sum", lateEvening), 5, lateEvening);

        assertThat(result.dueAt()).isEqualTo(Instant.parse("2026-01-02T00:00:00Z"));
    }

    @Test
    void anUnreviewedProblemIsDueImmediately() {
        ReviewSchedule initial = scheduler.initial("two-sum", NOW);

        assertThat(initial.dueAt()).isEqualTo(NOW);
        assertThat(initial.lastReviewedAt()).isNull();
        assertThat(initial.easinessFactor()).isEqualTo(SpacedRepetitionScheduler.INITIAL_EASINESS);
    }
}
