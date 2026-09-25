package com.ayush.leetcodecoach.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebFilterConfig {

    // The ingest endpoint is guarded by the same filter as /mcp: both let a caller change stored
    // state, and both are reachable from a browser on the same machine.

    @Bean
    FilterRegistrationBean<McpAccessFilter> mcpAccessFilterRegistration(McpAccessProperties properties) {
        FilterRegistrationBean<McpAccessFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new McpAccessFilter(properties));
        registration.addUrlPatterns("/mcp", "/mcp/*", "/api/sync/*");
        registration.setOrder(-100);
        return registration;
    }
}
