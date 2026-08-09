package com.ayush.leetcodecoach.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RemoteUserStatus(
        Boolean isSignedIn,
        String username,
        String realName,
        String avatar) {
}
