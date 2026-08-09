package com.ayush.leetcodecoach.domain;

import java.time.Instant;

public record Attempt(
        String id,
        String sessionId,
        String language,
        String code,
        String verdict,
        Long runtimeMs,
        Long memoryKb,
        String timeComplexity,
        String spaceComplexity,
        String notes,
        Instant createdAt) {
}
