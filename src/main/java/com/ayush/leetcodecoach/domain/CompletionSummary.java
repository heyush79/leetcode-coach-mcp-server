package com.ayush.leetcodecoach.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The result of completing a practice session: the closed session and the review it scheduled.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompletionSummary(PracticeSession session, ReviewOutcome review) {
}
