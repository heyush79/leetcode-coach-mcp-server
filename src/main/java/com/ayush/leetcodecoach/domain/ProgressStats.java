package com.ayush.leetcodecoach.domain;

import java.util.Map;

/**
 * @param reviewsDue problems whose spaced-repetition review is due now
 */
public record ProgressStats(
        long completedSessions,
        long acceptedAttempts,
        long totalAttempts,
        long activeDays,
        long currentStreak,
        long reviewsDue,
        Map<String, Long> completedByDifficulty) {
}
