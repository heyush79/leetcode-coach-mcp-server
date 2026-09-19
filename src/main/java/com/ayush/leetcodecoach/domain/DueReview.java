package com.ayush.leetcodecoach.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A problem whose review is due, with the context an agent needs to explain why.
 *
 * @param daysOverdue days past the due date; 0 means due today
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DueReview(
        String titleSlug,
        String title,
        Difficulty difficulty,
        ReviewSchedule schedule,
        long daysOverdue,
        String reason) {
}
