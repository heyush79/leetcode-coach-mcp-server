package com.ayush.leetcodecoach.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(
        @Nullable Long id,
        @Nullable String frontendId,
        String title,
        String titleSlug,
        Difficulty difficulty,
        boolean paidOnly,
        @Nullable String status,
        @Nullable Double acceptanceRate,
        @Nullable String statementHtml,
        @Nullable String sampleTestCase,
        List<String> hints,
        List<TopicTag> topicTags,
        List<CodeSnippet> codeSnippets,
        String source,
        Instant syncedAt) {
}
