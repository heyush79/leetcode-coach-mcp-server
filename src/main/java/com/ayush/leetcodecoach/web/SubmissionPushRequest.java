package com.ayush.leetcodecoach.web;

import com.ayush.leetcodecoach.integration.RemoteSubmission;
import java.util.List;

/**
 * A batch of submissions the browser extension read from leetcode.com.
 *
 * <p>Carries LeetCode's own submission objects unchanged, so the extension forwards what it received
 * rather than inventing a second shape that could drift from the one the pull path already parses.
 */
public record SubmissionPushRequest(List<RemoteSubmission> submissions) {
}
