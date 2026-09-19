package com.ayush.leetcodecoach.sync;

import com.ayush.leetcodecoach.config.LeetCodeProperties;
import com.ayush.leetcodecoach.domain.SyncReport;
import com.ayush.leetcodecoach.domain.SyncRun;
import com.ayush.leetcodecoach.domain.SyncStatus;
import com.ayush.leetcodecoach.integration.LeetCodeGraphQlClient;
import com.ayush.leetcodecoach.integration.LeetCodeIntegrationException;
import com.ayush.leetcodecoach.integration.RemoteSubmission;
import com.ayush.leetcodecoach.integration.RemoteSubmissionPage;
import com.ayush.leetcodecoach.repository.PracticeRepository;
import com.ayush.leetcodecoach.repository.SyncRepository;
import com.ayush.leetcodecoach.review.ReviewService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Pulls the user's submissions from leetcode.com and folds them into practice history.
 *
 * <p>This is what makes the coach see what actually happened rather than what it was told. Nothing
 * downstream distinguishes a synced session from an assistant-driven one: both are graded by the
 * same rules and rebuild the same review schedule.
 *
 * <p>Runs are incremental. LeetCode lists submissions newest first, so a run stops at the first
 * page containing a submission already stored. A {@code full} run keeps paging to the configured
 * cap, skipping known submissions, which backfills history the first bounded run did not reach.
 */
@Service
public class SubmissionSyncService {

    private static final Logger log = LoggerFactory.getLogger(SubmissionSyncService.class);

    private final LeetCodeGraphQlClient client;
    private final SubmissionImporter importer;
    private final ReviewService reviewService;
    private final SyncRepository syncRepository;
    private final PracticeRepository practiceRepository;
    private final LeetCodeProperties properties;
    private final Clock clock;

    /** One sync at a time: the scheduler and the on-demand tool must not interleave page reads. */
    private final AtomicBoolean running = new AtomicBoolean();

    public SubmissionSyncService(
            LeetCodeGraphQlClient client,
            SubmissionImporter importer,
            ReviewService reviewService,
            SyncRepository syncRepository,
            PracticeRepository practiceRepository,
            LeetCodeProperties properties,
            Clock clock) {
        this.client = client;
        this.importer = importer;
        this.reviewService = reviewService;
        this.syncRepository = syncRepository;
        this.practiceRepository = practiceRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Fetches new submissions, stores them, and rebuilds the review schedule of every problem they
     * touched.
     *
     * @param full keep paging past known submissions, to backfill older history
     */
    public SyncReport sync(boolean full) {
        if (!properties.isRemoteEnabled()) {
            return SyncReport.skipped("LeetCode remote integration is disabled (LEETCODE_REMOTE_ENABLED=false).");
        }
        if (!properties.credentialsConfigured()) {
            return SyncReport.skipped("LEETCODE_SESSION and LEETCODE_CSRF_TOKEN are not configured, "
                    + "so your submissions cannot be read from leetcode.com.");
        }
        if (!running.compareAndSet(false, true)) {
            return SyncReport.skipped("A sync is already running.");
        }

        Instant startedAt = Instant.now(clock);
        SyncRun run = new SyncRun(UUID.randomUUID().toString(), startedAt, null,
                SyncRun.STATUS_RUNNING, 0, 0, 0, 0, 0, null);
        syncRepository.start(run);

        Totals totals = new Totals();
        Set<String> affected = new LinkedHashSet<>();
        String status;
        String message;
        try {
            List<RemoteSubmission> fresh = fetchNewSubmissions(full, totals);
            for (RemoteSubmission submission : fresh) {
                importer.importSubmission(submission).ifPresent(outcome -> {
                    totals.imported++;
                    totals.sessionsCreated += outcome.sessionCreated() ? 1 : 0;
                    totals.sessionsUpdated += outcome.sessionUpdated() ? 1 : 0;
                    totals.problemsAdded += outcome.problemAdded() ? 1 : 0;
                    affected.add(outcome.titleSlug());
                });
            }
            for (String titleSlug : affected) {
                reviewService.rebuildSchedule(titleSlug);
            }
            status = SyncRun.STATUS_SUCCEEDED;
            message = describe(totals, affected);
            log.info("LeetCode sync: {}", message);
        }
        catch (LeetCodeIntegrationException ex) {
            status = SyncRun.STATUS_FAILED;
            message = ex.getMessage();
            log.warn("LeetCode sync failed: {}", message);
        }
        catch (RuntimeException ex) {
            status = SyncRun.STATUS_FAILED;
            message = "Unexpected failure during sync: " + ex.getMessage();
            log.error("LeetCode sync failed unexpectedly", ex);
        }
        finally {
            running.set(false);
        }

        SyncRun finished = new SyncRun(run.id(), startedAt, Instant.now(clock), status,
                totals.seen, totals.imported, totals.sessionsCreated, totals.sessionsUpdated,
                totals.problemsAdded, message);
        syncRepository.finish(finished);
        return new SyncReport(status, message, finished, List.copyOf(affected));
    }

    public SyncStatus status() {
        LeetCodeProperties.Sync sync = properties.getSync();
        boolean credentials = properties.credentialsConfigured();
        boolean remote = properties.isRemoteEnabled();
        Optional<SyncRun> lastRun = syncRepository.lastRun();

        String explanation;
        if (!remote) {
            explanation = "Remote integration is disabled, so submissions are not synced.";
        }
        else if (!credentials) {
            explanation = "Set LEETCODE_SESSION and LEETCODE_CSRF_TOKEN to sync your submissions from leetcode.com.";
        }
        else if (lastRun.isEmpty()) {
            explanation = sync.isEnabled()
                    ? "Credentials are configured; the first background sync has not run yet."
                    : "Credentials are configured; call sync_leetcode_submissions to sync now.";
        }
        else {
            explanation = "Last sync " + lastRun.get().status().toLowerCase() + " at " + lastRun.get().startedAt()
                    + (sync.isEnabled() ? "; background sync runs every " + sync.getIntervalSeconds() + " seconds." : ".");
        }

        return new SyncStatus(
                credentials,
                remote,
                sync.isEnabled(),
                sync.getIntervalSeconds(),
                practiceRepository.countSyncedAttempts(),
                lastRun.orElse(null),
                explanation);
    }

    /**
     * Pages through the listing, newest first, collecting submissions not yet stored. Returns them
     * oldest first so that sessions are inferred in the order they happened.
     */
    private List<RemoteSubmission> fetchNewSubmissions(boolean full, Totals totals) {
        LeetCodeProperties.Sync sync = properties.getSync();
        Map<String, RemoteSubmission> fresh = new LinkedHashMap<>();
        String lastKey = null;
        int offset = 0;

        for (int page = 0; page < sync.getMaxPages(); page++) {
            RemoteSubmissionPage result = client.fetchSubmissions(offset, sync.getPageSize(), lastKey);
            boolean sawKnown = false;

            for (RemoteSubmission submission : result.submissions()) {
                totals.seen++;
                if (submission.id() != null && practiceRepository.attemptExistsForSubmission(submission.id())) {
                    sawKnown = true;
                    continue;
                }
                if (submission.id() != null) {
                    fresh.putIfAbsent(submission.id(), submission);
                }
            }

            if (!Boolean.TRUE.equals(result.hasNext()) || result.submissions().isEmpty()) {
                break;
            }
            if (sawKnown && !full) {
                // Everything older than a stored submission was stored by an earlier run.
                break;
            }
            lastKey = result.lastKey();
            offset += result.submissions().size();
        }

        List<RemoteSubmission> ordered = new ArrayList<>(fresh.values());
        ordered.sort(Comparator.comparing(
                submission -> Optional.ofNullable(SubmissionParser.submittedAt(submission)).orElse(Instant.MAX)));
        return ordered;
    }

    private String describe(Totals totals, Set<String> affected) {
        if (totals.imported == 0) {
            return "No new submissions; " + totals.seen + " already known.";
        }
        return "Imported " + totals.imported + " new submission(s) across " + affected.size()
                + " problem(s): " + totals.sessionsCreated + " session(s) created, "
                + totals.sessionsUpdated + " extended, " + totals.problemsAdded + " problem(s) added to the catalog.";
    }

    /** Running counts for one sync. */
    private static final class Totals {
        int seen;
        int imported;
        int sessionsCreated;
        int sessionsUpdated;
        int problemsAdded;
    }
}
