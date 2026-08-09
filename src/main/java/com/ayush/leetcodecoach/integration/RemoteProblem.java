package com.ayush.leetcodecoach.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RemoteProblem(
        String questionFrontendId,
        String title,
        String titleSlug,
        String content,
        String difficulty,
        Boolean isPaidOnly,
        String status,
        String sampleTestCase,
        List<String> hints,
        List<RemoteTopicTag> topicTags,
        List<RemoteCodeSnippet> codeSnippets,
        String stats) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RemoteTopicTag(String name, String slug) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RemoteCodeSnippet(String lang, String langSlug, String code) {
    }
}
