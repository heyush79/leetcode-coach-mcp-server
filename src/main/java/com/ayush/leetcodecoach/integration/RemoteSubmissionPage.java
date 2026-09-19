package com.ayush.leetcodecoach.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** A page of {@code submissionList}, newest first. {@code lastKey} continues the listing. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RemoteSubmissionPage(String lastKey, Boolean hasNext, List<RemoteSubmission> submissions) {
}
