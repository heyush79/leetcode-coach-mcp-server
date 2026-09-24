package com.ayush.leetcodecoach.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "leetcode")
public class LeetCodeProperties {

    /** LeetCode's session cookie is a JWT: three base64url segments, several hundred characters. */
    private static final Pattern SESSION_SHAPE =
            Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    /** Django's csrftoken cookie: 64 letters and digits (32 on older deployments). */
    private static final Pattern CSRF_SHAPE = Pattern.compile("[A-Za-z0-9]{32,64}");

    private String endpoint = "https://leetcode.com/graphql";
    private String session = "";
    private String csrfToken = "";
    private String userAgent = "LeetCodeCoachMCP/1.0";
    private boolean remoteEnabled = true;
    private int timeoutSeconds = 8;

    /** Percentage of failed calls in the sliding window that trips the breaker. */
    private float failureRateThreshold = 50;

    /** Calls required before the failure rate is evaluated at all. */
    private int minimumCalls = 5;

    /** How many recent calls the failure rate is computed over. */
    private int slidingWindowSize = 10;

    /** How long the breaker stays open before probing the API again. */
    private int openStateSeconds = 30;

    private final Sync sync = new Sync();

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getSession() {
        return session;
    }

    public void setSession(String session) {
        this.session = session;
    }

    public String getCsrfToken() {
        return csrfToken;
    }

    public void setCsrfToken(String csrfToken) {
        this.csrfToken = csrfToken;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public boolean isRemoteEnabled() {
        return remoteEnabled;
    }

    public void setRemoteEnabled(boolean remoteEnabled) {
        this.remoteEnabled = remoteEnabled;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public float getFailureRateThreshold() {
        return failureRateThreshold;
    }

    public void setFailureRateThreshold(float failureRateThreshold) {
        this.failureRateThreshold = failureRateThreshold;
    }

    public int getMinimumCalls() {
        return minimumCalls;
    }

    public void setMinimumCalls(int minimumCalls) {
        this.minimumCalls = minimumCalls;
    }

    public int getSlidingWindowSize() {
        return slidingWindowSize;
    }

    public void setSlidingWindowSize(int slidingWindowSize) {
        this.slidingWindowSize = slidingWindowSize;
    }

    public int getOpenStateSeconds() {
        return openStateSeconds;
    }

    public void setOpenStateSeconds(int openStateSeconds) {
        this.openStateSeconds = openStateSeconds;
    }

    public Sync getSync() {
        return sync;
    }

    public boolean credentialsConfigured() {
        return session != null && !session.isBlank() && csrfToken != null && !csrfToken.isBlank();
    }

    /**
     * Why the configured credentials do not look like LeetCode's cookies, or empty when they do.
     *
     * <p>Advisory only: the values are still sent exactly as given, because LeetCode is the
     * authority on whether they are valid. This exists because the commonest setup mistake is
     * pasting the wrong cookie, and "LeetCode did not accept them" does not say which one. Only
     * lengths are reported, never the values.
     */
    public Optional<String> credentialShapeProblem() {
        if (!credentialsConfigured()) {
            return Optional.empty();
        }
        List<String> problems = new ArrayList<>();
        String trimmedSession = session.trim();
        if (!SESSION_SHAPE.matcher(trimmedSession).matches() || trimmedSession.length() < 100) {
            problems.add("LEETCODE_SESSION should be the value of the LEETCODE_SESSION cookie, a token of "
                    + "several hundred characters starting with 'eyJ' (the configured value has "
                    + trimmedSession.length() + " characters)");
        }
        String trimmedCsrf = csrfToken.trim();
        if (!CSRF_SHAPE.matcher(trimmedCsrf).matches()) {
            problems.add("LEETCODE_CSRF_TOKEN should be the value of the csrftoken cookie, 64 letters and "
                    + "digits (the configured value has " + trimmedCsrf.length() + " characters)");
        }
        return problems.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", problems));
    }

    /** Settings for pulling the user's own submissions from leetcode.com. */
    public static class Sync {

        /** Whether the background sync runs at all. The on-demand tool works regardless. */
        private boolean enabled = true;

        /** How often the background sync runs. */
        private int intervalSeconds = 900;

        /** How long after startup the first background sync runs. */
        private int initialDelaySeconds = 30;

        /**
         * Submissions to the same problem closer together than this are one practice session.
         * Further apart, the later one is a fresh attempt at recall and is graded separately.
         */
        private int sessionGapMinutes = 120;

        /**
         * A submission this soon after an assistant-driven session was completed is attached to
         * that session rather than starting a new one, because it is the same sitting.
         */
        private int manualSessionSlackMinutes = 30;

        /** Submissions per request. LeetCode's own client uses 20. */
        private int pageSize = 20;

        /** Upper bound on pages per run, so a first sync over a long history stays bounded. */
        private int maxPages = 25;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getIntervalSeconds() {
            return intervalSeconds;
        }

        public void setIntervalSeconds(int intervalSeconds) {
            this.intervalSeconds = intervalSeconds;
        }

        public int getInitialDelaySeconds() {
            return initialDelaySeconds;
        }

        public void setInitialDelaySeconds(int initialDelaySeconds) {
            this.initialDelaySeconds = initialDelaySeconds;
        }

        public int getSessionGapMinutes() {
            return sessionGapMinutes;
        }

        public void setSessionGapMinutes(int sessionGapMinutes) {
            this.sessionGapMinutes = sessionGapMinutes;
        }

        public int getManualSessionSlackMinutes() {
            return manualSessionSlackMinutes;
        }

        public void setManualSessionSlackMinutes(int manualSessionSlackMinutes) {
            this.manualSessionSlackMinutes = manualSessionSlackMinutes;
        }

        public int getPageSize() {
            return pageSize;
        }

        public void setPageSize(int pageSize) {
            this.pageSize = pageSize;
        }

        public int getMaxPages() {
            return maxPages;
        }

        public void setMaxPages(int maxPages) {
            this.maxPages = maxPages;
        }
    }
}
