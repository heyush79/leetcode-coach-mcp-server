package com.ayush.leetcodecoach.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.mcp-access")
public class McpAccessProperties {

    private String apiKey = "";
    private List<String> allowedOriginHosts = new ArrayList<>(List.of("localhost", "127.0.0.1"));

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public List<String> getAllowedOriginHosts() {
        return allowedOriginHosts;
    }

    public void setAllowedOriginHosts(List<String> allowedOriginHosts) {
        this.allowedOriginHosts = allowedOriginHosts;
    }

    public boolean apiKeyEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
