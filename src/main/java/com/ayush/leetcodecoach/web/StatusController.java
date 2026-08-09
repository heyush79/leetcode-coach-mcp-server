package com.ayush.leetcodecoach.web;

import com.ayush.leetcodecoach.config.LeetCodeProperties;
import com.ayush.leetcodecoach.config.McpAccessProperties;
import com.ayush.leetcodecoach.service.ProblemCatalogService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class StatusController {

    private final ProblemCatalogService catalogService;
    private final LeetCodeProperties leetCodeProperties;
    private final McpAccessProperties mcpAccessProperties;

    public StatusController(
            ProblemCatalogService catalogService,
            LeetCodeProperties leetCodeProperties,
            McpAccessProperties mcpAccessProperties) {
        this.catalogService = catalogService;
        this.leetCodeProperties = leetCodeProperties;
        this.mcpAccessProperties = mcpAccessProperties;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("service", "leetcode-coach-mcp-server");
        status.put("mcpEndpoint", "/mcp");
        status.put("transport", "STREAMABLE_HTTP_WITH_OPTIONAL_SSE");
        status.put("cachedProblems", catalogService.count());
        status.put("leetcodeRemoteEnabled", leetCodeProperties.isRemoteEnabled());
        status.put("leetcodeCredentialsConfigured", leetCodeProperties.credentialsConfigured());
        status.put("mcpApiKeyEnabled", mcpAccessProperties.apiKeyEnabled());
        return status;
    }
}
