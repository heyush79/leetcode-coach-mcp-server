package com.ayush.leetcodecoach.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LeetCodePropertiesTest {

    private static final String REALISTIC_SESSION =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9." + "a".repeat(400) + "." + "b".repeat(43);

    private static final String REALISTIC_CSRF =
            "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8S9t0U1v2W3x4Y5z6A7b8C9d0E1f2";

    @Test
    void acceptsValuesShapedLikeLeetCodeCookies() {
        LeetCodeProperties properties = new LeetCodeProperties();
        properties.setSession(REALISTIC_SESSION);
        properties.setCsrfToken(REALISTIC_CSRF);

        assertThat(properties.credentialsConfigured()).isTrue();
        assertThat(properties.credentialShapeProblem()).isEmpty();
    }

    @Test
    void namesEachValueThatDoesNotLookLikeItsCookieReportingOnlyLengths() {
        LeetCodeProperties properties = new LeetCodeProperties();
        properties.setSession("not-a-session-token!");
        properties.setCsrfToken("short=with=equals");

        String problem = properties.credentialShapeProblem().orElseThrow();

        assertThat(problem)
                .contains("LEETCODE_SESSION")
                .contains("starting with 'eyJ'")
                .contains("20 characters")
                .contains("LEETCODE_CSRF_TOKEN")
                .contains("17 characters")
                .doesNotContain("not-a-session-token")
                .doesNotContain("short=with=equals");
    }

    @Test
    void flagsOnlyTheValueThatIsWrong() {
        LeetCodeProperties properties = new LeetCodeProperties();
        properties.setSession(REALISTIC_SESSION);
        properties.setCsrfToken("your-csrf-cookie");

        String problem = properties.credentialShapeProblem().orElseThrow();

        assertThat(problem).contains("LEETCODE_CSRF_TOKEN").doesNotContain("LEETCODE_SESSION should");
    }

    @Test
    void saysNothingWhenNoCredentialsAreConfigured() {
        LeetCodeProperties properties = new LeetCodeProperties();

        assertThat(properties.credentialsConfigured()).isFalse();
        assertThat(properties.credentialShapeProblem()).isEmpty();
    }
}
