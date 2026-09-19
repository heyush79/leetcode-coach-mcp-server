package com.ayush.leetcodecoach.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "leetcode")
public class LeetCodeProperties {

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

    public boolean credentialsConfigured() {
        return session != null && !session.isBlank() && csrfToken != null && !csrfToken.isBlank();
    }
}
