package com.ayush.leetcodecoach.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.ayush.leetcodecoach.integration.RemoteSubmission;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SubmissionParserTest {

    @Test
    void normalisesVerdictsToTheFormRecordedByHand() {
        assertThat(SubmissionParser.verdict("Accepted")).isEqualTo("ACCEPTED");
        assertThat(SubmissionParser.verdict("Wrong Answer")).isEqualTo("WRONG_ANSWER");
        assertThat(SubmissionParser.verdict("  time limit exceeded ")).isEqualTo("TIME_LIMIT_EXCEEDED");
        assertThat(SubmissionParser.verdict("Runtime Error")).isEqualTo("RUNTIME_ERROR");
    }

    @Test
    void treatsAMissingVerdictAsUnassessed() {
        assertThat(SubmissionParser.verdict(null)).isEqualTo("UNASSESSED");
        assertThat(SubmissionParser.verdict("   ")).isEqualTo("UNASSESSED");
        assertThat(SubmissionParser.verdict("---")).isEqualTo("UNASSESSED");
    }

    @Test
    void readsEpochSecondsAndToleratesMilliseconds() {
        Instant expected = Instant.parse("2026-03-01T10:00:00Z");

        assertThat(SubmissionParser.submittedAt(submission("1772359200"))).isEqualTo(expected);
        assertThat(SubmissionParser.submittedAt(submission("1772359200000"))).isEqualTo(expected);
    }

    @Test
    void reportsAnUnreadableTimestampAsAbsent() {
        assertThat(SubmissionParser.submittedAt(submission("yesterday"))).isNull();
        assertThat(SubmissionParser.submittedAt(submission(null))).isNull();
        assertThat(SubmissionParser.submittedAt(submission(""))).isNull();
    }

    @Test
    void parsesRuntimeDisplayStrings() {
        assertThat(SubmissionParser.runtimeMs("52 ms")).isEqualTo(52L);
        assertThat(SubmissionParser.runtimeMs("1.6 ms")).isEqualTo(2L);
        assertThat(SubmissionParser.runtimeMs("N/A")).isNull();
        assertThat(SubmissionParser.runtimeMs(null)).isNull();
    }

    @Test
    void parsesMemoryDisplayStringsIntoKilobytes() {
        assertThat(SubmissionParser.memoryKb("16.4 MB")).isEqualTo(16794L);
        assertThat(SubmissionParser.memoryKb("512 KB")).isEqualTo(512L);
        assertThat(SubmissionParser.memoryKb("N/A")).isNull();
        assertThat(SubmissionParser.memoryKb(null)).isNull();
    }

    @Test
    void recognisesPendingSubmissionsInEitherEncoding() {
        assertThat(SubmissionParser.isPending(pending("Pending", ""))).isTrue();
        assertThat(SubmissionParser.isPending(pending("true", "Accepted"))).isTrue();
        assertThat(SubmissionParser.isPending(pending("Not Pending", ""))).isTrue();
        assertThat(SubmissionParser.isPending(pending("Not Pending", "Accepted"))).isFalse();
        assertThat(SubmissionParser.isPending(pending(null, "Wrong Answer"))).isFalse();
    }

    private RemoteSubmission submission(String timestamp) {
        return new RemoteSubmission("1", "Two Sum", "two-sum", "Accepted", "python3",
                "52 ms", "16.4 MB", timestamp, "Not Pending", "/submissions/detail/1/");
    }

    private RemoteSubmission pending(String isPending, String statusDisplay) {
        return new RemoteSubmission("1", "Two Sum", "two-sum", statusDisplay, "python3",
                null, null, "1772359200", isPending, null);
    }
}
