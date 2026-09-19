package com.ayush.leetcodecoach.sync;

import com.ayush.leetcodecoach.integration.RemoteSubmission;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Interprets the display strings LeetCode returns in its submission list.
 *
 * <p>Kept separate and pure so that the one place LeetCode's formatting can break is also the one
 * place it is tested.
 */
final class SubmissionParser {

    private static final Pattern RUNTIME = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*ms", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEMORY = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(MB|KB)", Pattern.CASE_INSENSITIVE);

    /** Timestamps at or above this are treated as milliseconds rather than seconds. */
    private static final long MILLISECOND_THRESHOLD = 100_000_000_000L;

    private SubmissionParser() {
    }

    /** A submission still being judged has no verdict yet and is picked up on a later sync. */
    static boolean isPending(RemoteSubmission submission) {
        String pending = submission.isPending();
        if (pending != null && (pending.equalsIgnoreCase("pending") || pending.equalsIgnoreCase("true"))) {
            return true;
        }
        return submission.statusDisplay() == null || submission.statusDisplay().isBlank();
    }

    /** LeetCode sends epoch seconds as a string; tolerate a number and milliseconds too. */
    static @Nullable Instant submittedAt(RemoteSubmission submission) {
        if (submission.timestamp() == null || submission.timestamp().isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(submission.timestamp().trim());
            return value >= MILLISECOND_THRESHOLD ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    /** {@code "Wrong Answer"} becomes {@code WRONG_ANSWER}, matching the verdicts recorded by hand. */
    static String verdict(@Nullable String statusDisplay) {
        if (statusDisplay == null || statusDisplay.isBlank()) {
            return "UNASSESSED";
        }
        String normalized = statusDisplay.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isEmpty() ? "UNASSESSED" : normalized;
    }

    static @Nullable Long runtimeMs(@Nullable String runtime) {
        if (runtime == null) {
            return null;
        }
        Matcher matcher = RUNTIME.matcher(runtime);
        return matcher.find() ? Math.round(Double.parseDouble(matcher.group(1))) : null;
    }

    static @Nullable Long memoryKb(@Nullable String memory) {
        if (memory == null) {
            return null;
        }
        Matcher matcher = MEMORY.matcher(memory);
        if (!matcher.find()) {
            return null;
        }
        double amount = Double.parseDouble(matcher.group(1));
        boolean megabytes = matcher.group(2).equalsIgnoreCase("MB");
        return Math.round(megabytes ? amount * 1024 : amount);
    }
}
