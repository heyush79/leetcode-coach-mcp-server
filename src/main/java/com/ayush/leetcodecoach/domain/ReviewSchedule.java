package com.ayush.leetcodecoach.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * SM-2 review state for one problem.
 *
 * @param easinessFactor how quickly the interval grows for this problem; never below 1.3
 * @param intervalDays days between the last review and the next one
 * @param repetitions consecutive successful recalls, reset to zero by a lapse
 * @param lapses how many times a previously learned problem was forgotten
 * @param lastGrade the most recent recall grade, 0 to 5
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewSchedule(
        String titleSlug,
        double easinessFactor,
        int intervalDays,
        int repetitions,
        int lapses,
        int lastGrade,
        @Nullable Instant lastReviewedAt,
        Instant dueAt) {
}
