package com.ayush.leetcodecoach.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * Whether submissions can flow in from leetcode.com, and when they last did.
 *
 * @param schedulerEnabled whether the background sync is configured to run
 * @param intervalSeconds how often the background sync runs when enabled
 * @param syncedSubmissions total submissions imported from LeetCode across all runs
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SyncStatus(
        boolean credentialsConfigured,
        boolean remoteEnabled,
        boolean schedulerEnabled,
        int intervalSeconds,
        long syncedSubmissions,
        @Nullable SyncRun lastRun,
        String explanation) {
}
