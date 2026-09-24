package com.ayush.leetcodecoach.sync;

import com.ayush.leetcodecoach.config.LeetCodeProperties;
import com.ayush.leetcodecoach.domain.SyncReport;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the submission sync in the background, so practising on leetcode.com is enough: the coach
 * catches up on its own without the user having to report anything.
 *
 * <p>Quietly does nothing until credentials are configured. The on-demand MCP tool is unaffected by
 * the {@code enabled} flag and reports why it skipped, so a user who has not set things up learns
 * that from the assistant rather than from a log they never read.
 */
@Component
public class SubmissionSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubmissionSyncScheduler.class);

    private final SubmissionSyncService syncService;
    private final LeetCodeProperties properties;

    public SubmissionSyncScheduler(SubmissionSyncService syncService, LeetCodeProperties properties) {
        this.syncService = syncService;
        this.properties = properties;
    }

    @Scheduled(
            initialDelayString = "${leetcode.sync.initial-delay-seconds:30}",
            fixedDelayString = "${leetcode.sync.interval-seconds:900}",
            timeUnit = TimeUnit.SECONDS)
    public void syncIfConfigured() {
        if (!properties.getSync().isEnabled() || !properties.isRemoteEnabled() || !properties.credentialsConfigured()) {
            log.debug("Background LeetCode sync skipped: not enabled or no credentials");
            return;
        }
        SyncReport report = syncService.sync(false);
        if (SyncReport.STATUS_SKIPPED.equals(report.status())) {
            log.debug("Background LeetCode sync skipped: {}", report.message());
        }
    }
}
