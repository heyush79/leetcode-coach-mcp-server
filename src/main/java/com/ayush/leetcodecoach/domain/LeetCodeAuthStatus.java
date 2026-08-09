package com.ayush.leetcodecoach.domain;

public record LeetCodeAuthStatus(
        boolean credentialsConfigured,
        boolean authenticated,
        String username,
        String realName,
        String avatar,
        String message) {
}
