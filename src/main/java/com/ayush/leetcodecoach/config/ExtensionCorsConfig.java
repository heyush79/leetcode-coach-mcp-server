package com.ayush.leetcodecoach.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the browser extension post submissions to the local server.
 *
 * <p>A Manifest V3 service worker can usually reach a host in its {@code host_permissions} without
 * CORS, but an extension page or content script cannot, and the failure looks like a silent network
 * error. Declaring it here means the extension works however it chooses to make the call.
 *
 * <p>Only extension origins, and only the ingest path. A web page cannot use this: the browser will
 * not let {@code https://anything.example} send here, which is what keeps a hostile site from
 * writing into a server listening on localhost.
 */
@Configuration
public class ExtensionCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/sync/**")
                .allowedOriginPatterns("chrome-extension://*", "moz-extension://*")
                .allowedMethods("POST", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
