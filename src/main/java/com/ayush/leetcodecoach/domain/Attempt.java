package com.ayush.leetcodecoach.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

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
        Instant createdAt) {
}
