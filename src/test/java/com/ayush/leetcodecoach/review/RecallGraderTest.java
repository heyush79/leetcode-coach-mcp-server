package com.ayush.leetcodecoach.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.ayush.leetcodecoach.domain.RecallSignal;
import org.junit.jupiter.api.Test;

class RecallGraderTest {

    private final RecallGrader grader = new RecallGrader();

    @Test
    void anUnaidedSolveWithinBudgetIsAPerfectRecall() {
        assertThat(grader.grade(signal(1, 0, true, 0, 20))).isEqualTo(5);
    }

    @Test
    void eachHintCostsAGradePointUpToTwo() {
        assertThat(grader.grade(signal(1, 0, true, 1, 20))).isEqualTo(4);
        assertThat(grader.grade(signal(1, 0, true, 2, 20))).isEqualTo(3);
        assertThat(grader.grade(signal(1, 0, true, 3, 20))).isEqualTo(3);
    }

    @Test
    void failedAttemptsBeforeSuccessCostAGradePointUpToTwo() {
        assertThat(grader.grade(signal(2, 1, true, 0, 20))).isEqualTo(4);
        assertThat(grader.grade(signal(3, 2, true, 0, 20))).isEqualTo(3);
        assertThat(grader.grade(signal(6, 5, true, 0, 20))).isEqualTo(3);
    }

    @Test
    void runningWellOverTheTimeBudgetCostsAGradePoint() {
        assertThat(grader.grade(signal(1, 0, true, 0, 46))).isEqualTo(4);

        // Merely exceeding the budget is not enough; the penalty starts at 1.5x.
        assertThat(grader.grade(signal(1, 0, true, 0, 44))).isEqualTo(5);
    }

    @Test
    void anAcceptedSolutionNeverGradesBelowTwoHoweverMuchHelpItTook() {
        assertThat(grader.grade(signal(5, 4, true, 3, 200))).isEqualTo(2);
    }

    @Test
    void aSessionWithNoAcceptedAttemptGradesAsAFailure() {
        assertThat(grader.grade(signal(3, 0, false, 1, 30))).isEqualTo(1);
    }

    @Test
    void aSessionWithNoAttemptsAtAllGradesAsBlank() {
        assertThat(grader.grade(signal(0, 0, false, 0, 5))).isZero();
    }

    @Test
    void gradesBelowThePassMarkTriggerARepetitionReset() {
        // The contract between grader and scheduler: a struggled-through solve still resets.
        int struggled = grader.grade(signal(5, 4, true, 3, 200));

        assertThat(struggled).isLessThan(SpacedRepetitionScheduler.PASSING_GRADE);
    }

    @Test
    void explanationNamesWhatCostTheGrade() {
        RecallSignal signal = signal(3, 2, true, 1, 60);

        String explanation = grader.explain(signal, grader.grade(signal));

        assertThat(explanation)
                .contains("1 hint")
                .contains("2 failed attempt")
                .contains("over the 30-minute budget");
    }

    @Test
    void explanationCallsOutACleanSolve() {
        RecallSignal signal = signal(1, 0, true, 0, 10);

        assertThat(grader.explain(signal, 5)).contains("unaided and within budget");
    }

    @Test
    void missingTimeBudgetDisablesTheOvertimePenalty() {
        RecallSignal noBudget = new RecallSignal(1, 0, true, 0, 600, 0);

        assertThat(grader.grade(noBudget)).isEqualTo(5);
    }

    private RecallSignal signal(
            int totalAttempts, int attemptsBeforeAccepted, boolean accepted, int hints, long elapsedMinutes) {
        return new RecallSignal(totalAttempts, attemptsBeforeAccepted, accepted, hints, elapsedMinutes, 30);
    }
}
