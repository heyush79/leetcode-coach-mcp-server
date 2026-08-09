package com.ayush.leetcodecoach.domain;

import java.time.Instant;

public record PracticeSession(
        String id,
        String titleSlug,
        String status,
        Instant startedAt,
        Instant completedAt,
        Integer targetMinutes,
        String notes) {
}
