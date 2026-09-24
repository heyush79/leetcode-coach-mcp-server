package com.ayush.leetcodecoach.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One pass over leetcode.com's submission list.
 *
 * @param submissionsSeen submissions returned by LeetCode during this run, new or not
 * @param submissionsImported submissions that were not already stored
 * @param sessionsCreated practice sessions inferred from those submissions
 * @param sessionsUpdated existing sessions that gained a synced attempt
 * @param problemsAdded problems first seen during this run and added to the catalog
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SyncRun(
        String id,
        Instant startedAt,
        @Nullable Instant finishedAt,
        String status,
        int submissionsSeen,
        int submissionsImported,
        int sessionsCreated,
        int sessionsUpdated,
        int problemsAdded,
        @Nullable String message) {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
}
