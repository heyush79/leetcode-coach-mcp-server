package com.ayush.leetcodecoach.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What completing a practice session did to the problem's review schedule.
 *
 * @param grade the derived recall grade, 0 to 5
 * @param rationale why that grade was assigned, in terms the agent can relay
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewOutcome(
        String titleSlug,
        int grade,
        String rationale,
        ReviewSchedule schedule,
        int nextReviewInDays) {
}
