package com.ayush.leetcodecoach.mcp;

import com.ayush.leetcodecoach.service.ProblemCatalogService;
import tools.jackson.databind.ObjectMapper;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

@Component
public class LeetCodeProblemResource {

    private final ProblemCatalogService catalogService;
    private final ObjectMapper objectMapper;

    public LeetCodeProblemResource(ProblemCatalogService catalogService, ObjectMapper objectMapper) {
        this.catalogService = catalogService;
        this.objectMapper = objectMapper;
    }

    @McpResource(
            uri = "leetcode://problem/{titleSlug}",
            name = "leetcode-problem",
            title = "Cached LeetCode Problem",
            description = "Returns a problem from SQLite, refreshing from GraphQL if necessary.",
            mimeType = "application/json")
    public String problem(String titleSlug) {
        try {
            return objectMapper.writeValueAsString(catalogService.getProblem(titleSlug, false));
        }
        catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize problem resource", ex);
        }
    }
}

