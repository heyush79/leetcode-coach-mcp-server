package com.ayush.leetcodecoach.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards the endpoints that an outside page must not be able to reach: the MCP endpoint and the
 * submission ingest endpoint.
 *
 * <p>The Origin check is what stops a hostile web page from driving a server listening on localhost.
 * Requests without an Origin, such as a server-to-server MCP client or curl, are allowed, because
 * the header is set by browsers and its absence means no page is behind the request.
 */
public class McpAccessFilter extends OncePerRequestFilter {

    /** Paths this filter protects. Kept in step with the registration in {@link WebFilterConfig}. */
    private static final List<String> PROTECTED_PATHS = List.of("/mcp", "/api/sync");

    /**
     * Browser extensions send an opaque {@code chrome-extension://<id>} origin. The id is only known
     * once the extension is packed, and differs per install when loaded unpacked, so the scheme is
     * what is trusted. That is a deliberate widening: it admits any installed extension, which is a
     * far smaller concern than it sounds, since an extension able to reach localhost already holds
     * permissions well beyond what this endpoint offers.
     */
    private static final List<String> ALLOWED_ORIGIN_SCHEMES = List.of("chrome-extension", "moz-extension");

    private final McpAccessProperties properties;

    public McpAccessFilter(McpAccessProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return PROTECTED_PATHS.stream().noneMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!originAllowed(request.getHeader(HttpHeaders.ORIGIN))) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "Origin is not allowed");
            return;
        }

        // A CORS preflight cannot carry the key: the browser sends it before the real request and
        // strips custom headers. Rejecting it here would block the request that does carry the key.
        boolean preflight = HttpMethod.OPTIONS.matches(request.getMethod());
        if (!preflight && properties.apiKeyEnabled() && !apiKeyMatches(request)) {
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
            URI uri = URI.create(origin);
            String scheme = uri.getScheme();
            if (scheme != null && ALLOWED_ORIGIN_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
                return true;
            }
            String host = uri.getHost();
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
