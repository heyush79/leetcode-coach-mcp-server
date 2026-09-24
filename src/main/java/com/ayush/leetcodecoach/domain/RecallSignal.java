package com.ayush.leetcodecoach.domain;

/**
 * What a completed practice session observed about the user's recall.
 *
 * <p>These are measured, not self-reported. Classic spaced-repetition systems ask the learner to
 * rate their own recall, which is noisy; a coding session already reveals the same information
 * through how many attempts it took, how much help was needed, and how long it ran.
 *
 * @param attemptsBeforeFirstAccepted failed attempts preceding the first accepted one
 * @param hintsRevealed the highest hint level the user asked for, 0 if none
 * @param elapsedMinutes wall-clock duration of the session
 * @param targetMinutes the session's own time budget, or 0 when none was set
 */
public record RecallSignal(
        int totalAttempts,
        int attemptsBeforeFirstAccepted,
        boolean accepted,
        int hintsRevealed,
        long elapsedMinutes,
        int targetMinutes) {
}
