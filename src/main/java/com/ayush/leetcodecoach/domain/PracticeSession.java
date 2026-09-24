package com.ayush.leetcodecoach.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * @param maxHintLevel the highest hint level revealed during the session, used to grade recall
 * @param source {@code MANUAL} when driven through the MCP tools, {@code LEETCODE} when inferred
 *     from synced leetcode.com submissions
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
        int maxHintLevel,
        String source) {

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_LEETCODE = "LEETCODE";

    @JsonIgnore
    public boolean isManual() {
        return SOURCE_MANUAL.equals(source);
    }
}
