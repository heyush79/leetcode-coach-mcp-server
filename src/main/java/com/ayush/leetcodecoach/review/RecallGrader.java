package com.ayush.leetcodecoach.review;

import com.ayush.leetcodecoach.domain.RecallSignal;
import org.springframework.stereotype.Component;

/**
 * Turns what a practice session observed into an SM-2 recall grade from 0 to 5.
 *
 * <p>Self-rated recall is the usual input to SM-2, but a learner who has just read a hint is a poor
 * judge of whether they would have recalled the idea unaided. A coding session already measured
 * that, so the grade is derived from attempts, hints, and time instead of asked for.
 */
@Component
public class RecallGrader {

    /** An accepted solution never grades below this, however much help it took. */
    private static final int MINIMUM_ACCEPTED_GRADE = 2;

    /** Past this multiple of the session's own time budget, the solve counts as laboured. */
    private static final double OVERTIME_MULTIPLIER = 1.5;

    public int grade(RecallSignal signal) {
        if (signal.totalAttempts() == 0) {
            // Abandoned without writing anything: treat as a blank.
            return 0;
        }
        if (!signal.accepted()) {
            return 1;
        }

        int grade = 5;
        grade -= Math.min(2, signal.hintsRevealed());
        grade -= Math.min(2, Math.max(0, signal.attemptsBeforeFirstAccepted()));
        if (isOvertime(signal)) {
            grade -= 1;
        }
        return Math.max(MINIMUM_ACCEPTED_GRADE, grade);
    }

    /** Explains a grade in the terms the agent should relay to the user. */
    public String explain(RecallSignal signal, int grade) {
        if (signal.totalAttempts() == 0) {
            return "No attempt was recorded, so this problem is scheduled as unlearned.";
        }
        if (!signal.accepted()) {
            return "No attempt was accepted, so this problem returns tomorrow.";
        }

        StringBuilder reasons = new StringBuilder("Accepted");
        if (signal.hintsRevealed() > 0) {
            reasons.append(" after ").append(signal.hintsRevealed()).append(" hint(s)");
        }
        if (signal.attemptsBeforeFirstAccepted() > 0) {
            reasons.append(", ").append(signal.attemptsBeforeFirstAccepted()).append(" failed attempt(s)");
        }
        if (isOvertime(signal)) {
            reasons.append(", over the ").append(signal.targetMinutes()).append("-minute budget");
        }
        if (signal.hintsRevealed() == 0 && signal.attemptsBeforeFirstAccepted() == 0 && !isOvertime(signal)) {
            reasons.append(" unaided and within budget");
        }
        return reasons.append(". Recall graded ").append(grade).append("/5.").toString();
    }

    private boolean isOvertime(RecallSignal signal) {
        return signal.targetMinutes() > 0
                && signal.elapsedMinutes() > signal.targetMinutes() * OVERTIME_MULTIPLIER;
    }
}
