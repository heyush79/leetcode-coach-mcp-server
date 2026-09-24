package com.ayush.leetcodecoach.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Circuit breaker for the LeetCode GraphQL endpoint.
 *
 * <p>The client is synchronous and runs on a request thread, so a LeetCode outage would otherwise
 * make every tool call wait out the full timeout before falling back to SQLite. Once the breaker is
 * open the call fails immediately and the cached catalog answers instead, which is the same result
 * several seconds sooner.
 *
 * <p>The breaker only protects the remote call. It never suppresses the fallback: an open breaker
 * surfaces as a {@code LeetCodeIntegrationException} exactly like a timeout, and the catalog service
 * handles both the same way.
 */
@Configuration(proxyBeanMethods = false)
public class LeetCodeCircuitBreakerConfig {

    private static final Logger log = LoggerFactory.getLogger(LeetCodeCircuitBreakerConfig.class);

    @Bean
    public CircuitBreaker leetCodeCircuitBreaker(LeetCodeProperties properties) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(properties.getFailureRateThreshold())
                .minimumNumberOfCalls(properties.getMinimumCalls())
                .slidingWindowSize(properties.getSlidingWindowSize())
                .waitDurationInOpenState(Duration.ofSeconds(properties.getOpenStateSeconds()))
                .permittedNumberOfCallsInHalfOpenState(2)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        CircuitBreaker breaker = CircuitBreaker.of("leetcode-graphql", config);
        breaker.getEventPublisher().onStateTransition(event ->
                log.warn("LeetCode GraphQL circuit breaker {}", event.getStateTransition()));
        return breaker;
    }
}
