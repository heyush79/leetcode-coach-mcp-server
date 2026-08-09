package com.ayush.leetcodecoach.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class LeetCodeGraphQlConfig {

    @Bean
    HttpSyncGraphQlClient graphQlClient(LeetCodeProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()));

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

        return HttpSyncGraphQlClient.create(builder.build());
    }
}
