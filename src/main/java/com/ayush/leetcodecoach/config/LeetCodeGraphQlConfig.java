package com.ayush.leetcodecoach.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class LeetCodeGraphQlConfig {

    /** The client for interactive tool calls: problem lookups, search, the auth check. */
    @Bean
    @Primary
    HttpSyncGraphQlClient graphQlClient(LeetCodeProperties properties) {
        return HttpSyncGraphQlClient.create(restClient(properties, properties.getTimeoutSeconds()));
    }

    /**
     * The client for the submission sync, identical except for a longer read timeout. LeetCode's
     * submission list is slow on a cold cache, and a background job can afford to wait for it in
     * a way a tool call answering a user cannot.
     */
    @Bean
    HttpSyncGraphQlClient submissionSyncGraphQlClient(LeetCodeProperties properties) {
        return HttpSyncGraphQlClient.create(restClient(properties, properties.getSync().getTimeoutSeconds()));
    }

    private RestClient restClient(LeetCodeProperties properties, int readTimeoutSeconds) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.valueOf(properties.getHttpVersion().trim().toUpperCase()))
                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));

        RestClient.Builder builder = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(properties.getEndpoint())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.getUserAgent())
                .defaultHeader(HttpHeaders.ORIGIN, "https://leetcode.com")
                .defaultHeader(HttpHeaders.REFERER, "https://leetcode.com/");

        if (properties.credentialsConfigured()) {
            builder.defaultHeader(HttpHeaders.COOKIE,
                    "LEETCODE_SESSION=" + properties.getSession() + "; csrftoken=" + properties.getCsrfToken());
            builder.defaultHeader("x-csrftoken", properties.getCsrfToken());
        }

        return builder.build();
    }
}
