package com.ayush.leetcodecoach.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RemoteQuestionList(Integer total, List<RemoteProblemSummary> questions) {
}
