package com.ayush.leetcodecoach.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Aggregated recall performance for one topic tag.
 *
 * @param masteryScore 0.0 to 1.0, average recall quality discounted by how often the topic lapsed
 * @param label a plain-language band for {@code masteryScore}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TopicMastery(
        String topic,
        String topicSlug,
        int problemsReviewed,
        double averageGrade,
        int lapses,
        double masteryScore,
        String label) {
}
