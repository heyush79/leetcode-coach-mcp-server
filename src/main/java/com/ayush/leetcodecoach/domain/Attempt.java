package com.ayush.leetcodecoach.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * @param code the submitted source, or empty for a synced submission (LeetCode does not return
 *     source in its submission list)
 * @param source {@code MANUAL} when recorded through the MCP tools, {@code LEETCODE} when synced
 * @param leetcodeSubmissionId LeetCode's own id for a synced submission; the sync idempotency key
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Attempt(
        String id,
        String sessionId,
        String language,
        String code,
        String verdict,
        @Nullable Long runtimeMs,
        @Nullable Long memoryKb,
        @Nullable String timeComplexity,
        @Nullable String spaceComplexity,
        @Nullable String notes,
        Instant createdAt,
        String source,
        @Nullable String leetcodeSubmissionId) {

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_LEETCODE = "LEETCODE";
}
