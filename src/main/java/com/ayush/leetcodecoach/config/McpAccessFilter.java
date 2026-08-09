package com.ayush.leetcodecoach.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

public class McpAccessFilter extends OncePerRequestFilter {

    private final McpAccessProperties properties;

    public McpAccessFilter(McpAccessProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/mcp");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!originAllowed(request.getHeader(HttpHeaders.ORIGIN))) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "Origin is not allowed");
            return;
        }

        if (properties.apiKeyEnabled() && !apiKeyMatches(request)) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid MCP API key");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean originAllowed(String origin) {
        if (origin == null || origin.isBlank()) {
            return true;
        }
        try {
            String host = URI.create(origin).getHost();
            return host != null && properties.getAllowedOriginHosts().stream().anyMatch(host::equalsIgnoreCase);
        }
        catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean apiKeyMatches(HttpServletRequest request) {
        String supplied = request.getHeader("X-API-Key");
        if (supplied == null || supplied.isBlank()) {
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && authorization.startsWith("Bearer ")) {
                supplied = authorization.substring(7);
            }
        }
        return Objects.equals(properties.getApiKey(), supplied);
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
