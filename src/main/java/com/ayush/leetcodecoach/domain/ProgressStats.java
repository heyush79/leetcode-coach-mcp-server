package com.ayush.leetcodecoach.domain;

import java.util.Map;

public record ProgressStats(
        long completedSessions,
        long acceptedAttempts,
        long totalAttempts,
        long activeDays,
        long currentStreak,
        Map<String, Long> completedByDifficulty) {
}
