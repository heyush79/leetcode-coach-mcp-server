package com.ayush.leetcodecoach.domain;

import java.util.List;

/** Per-topic recall performance. See {@link ProblemSearchResult} for why this is not a bare list. */
public record TopicMasteryReport(List<TopicMastery> topics, int count) {

    public static TopicMasteryReport of(List<TopicMastery> topics) {
        return new TopicMasteryReport(topics, topics.size());
    }
}
