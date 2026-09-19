package com.ayush.leetcodecoach.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LeetCodeAuthStatus(
        boolean credentialsConfigured,
        boolean authenticated,
        @Nullable String username,
        @Nullable String realName,
        @Nullable String avatar,
        String message) {
}
