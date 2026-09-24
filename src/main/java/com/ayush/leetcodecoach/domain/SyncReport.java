package com.ayush.leetcodecoach.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a sync request did, in terms an agent can relay.
 *
 * @param status {@code SUCCEEDED}, {@code FAILED}, or {@code SKIPPED} when nothing was attempted
 *     (no credentials, remote disabled, or a sync already in progress)
 * @param updatedProblems slugs whose review schedule was rebuilt because new submissions arrived
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SyncReport(
        String status,
        String message,
        @Nullable SyncRun run,
        List<String> updatedProblems) {

    public static final String STATUS_SKIPPED = "SKIPPED";

    public static SyncReport skipped(String message) {
        return new SyncReport(STATUS_SKIPPED, message, null, List.of());
    }
}
