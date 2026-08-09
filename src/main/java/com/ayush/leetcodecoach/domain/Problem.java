package com.ayush.leetcodecoach.domain;

import java.time.Instant;
import java.util.List;

public record Problem(
        Long id,
        String frontendId,
        String title,
        String titleSlug,
        Difficulty difficulty,
        boolean paidOnly,
        String status,
        Double acceptanceRate,
        String statementHtml,
        String sampleTestCase,
        List<String> hints,
        List<TopicTag> topicTags,
        List<CodeSnippet> codeSnippets,
        String source,
        Instant syncedAt) {
}
