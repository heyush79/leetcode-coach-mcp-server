package com.ayush.leetcodecoach.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RemoteProblemSummary(
        String frontendQuestionId,
        String title,
        String titleSlug,
        String difficulty,
        Boolean paidOnly,
        String status,
        Double acRate,
        List<RemoteProblem.RemoteTopicTag> topicTags) {
}
