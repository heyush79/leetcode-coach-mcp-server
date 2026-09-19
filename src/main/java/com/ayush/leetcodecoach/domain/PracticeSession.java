package com.ayush.leetcodecoach.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * @param maxHintLevel the highest hint level revealed during the session, used to grade recall
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PracticeSession(
        String id,
        String titleSlug,
        String status,
        Instant startedAt,
        @Nullable Instant completedAt,
        @Nullable Integer targetMinutes,
        @Nullable String notes,
        int maxHintLevel) {
}
