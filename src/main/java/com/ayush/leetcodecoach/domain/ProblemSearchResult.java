package com.ayush.leetcodecoach.domain;

import java.util.List;

/**
 * Problems matching a search.
 *
 * <p>Wrapped in a record rather than returned as a bare list because MCP requires a tool's
 * {@code structuredContent} to be a JSON object. A top-level array is rejected by spec-compliant
 * clients. The count saves the agent from counting the list to answer "how many".
 */
public record ProblemSearchResult(List<Problem> problems, int count) {

    public static ProblemSearchResult of(List<Problem> problems) {
        return new ProblemSearchResult(problems, problems.size());
    }
}
