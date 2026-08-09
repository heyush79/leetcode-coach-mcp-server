package com.ayush.leetcodecoach.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebFilterConfig {

    @Bean
    FilterRegistrationBean<McpAccessFilter> mcpAccessFilterRegistration(McpAccessProperties properties) {
        FilterRegistrationBean<McpAccessFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new McpAccessFilter(properties));
        registration.addUrlPatterns("/mcp", "/mcp/*");
        registration.setOrder(-100);
        return registration;
    }
}
