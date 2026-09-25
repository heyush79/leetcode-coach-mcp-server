package com.ayush.leetcodecoach.web;

import com.ayush.leetcodecoach.domain.SyncReport;
import com.ayush.leetcodecoach.sync.SubmissionSyncService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives submissions read by the browser extension.
 *
 * <p>The counterpart to the server pulling them itself. Here the user's own browser calls LeetCode,
 * where it is already signed in, so the server never holds a session cookie and the traffic leaves
 * from the user's IP rather than the server's. Everything after this point is the same pipeline the
 * pull path uses.
 *
 * <p>Protected like {@code /mcp}: bound to localhost by default, and behind {@code MCP_API_KEY} when
 * one is set.
 */
@RestController
@RequestMapping("/api/sync")
public class SubmissionIngestController {

    /**
     * Submissions accepted per request. LeetCode pages twenty at a time, so this allows a healthy
     * batch while keeping one request bounded.
     */
    private static final int MAX_BATCH = 500;

    private final SubmissionSyncService syncService;

    public SubmissionIngestController(SubmissionSyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping("/submissions")
    public SyncReport ingest(@RequestBody SubmissionPushRequest request) {
        if (request == null || request.submissions() == null) {
            throw new IllegalArgumentException("A 'submissions' array is required");
        }
        if (request.submissions().size() > MAX_BATCH) {
            throw new IllegalArgumentException(
                    "At most " + MAX_BATCH + " submissions per request; send them in batches");
        }
        return syncService.ingest(request.submissions());
    }
}
