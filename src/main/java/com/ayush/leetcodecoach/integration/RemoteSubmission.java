package com.ayush.leetcodecoach.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One entry from LeetCode's {@code submissionList}.
 *
 * <p>{@code timestamp} arrives as epoch seconds in a string, {@code runtime} and {@code memory} as
 * display strings such as {@code "52 ms"} and {@code "16.4 MB"}, and {@code isPending} as the
 * string {@code "Not Pending"}. They are kept raw here and interpreted in one place, so a change in
 * LeetCode's formatting has one place to break.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RemoteSubmission(
        String id,
        String title,
        String titleSlug,
        String statusDisplay,
        String lang,
        String runtime,
        String memory,
        String timestamp,
        String isPending,
        String url) {
}
