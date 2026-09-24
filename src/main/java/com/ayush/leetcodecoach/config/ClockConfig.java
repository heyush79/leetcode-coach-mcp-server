package com.ayush.leetcodecoach.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the clock the practice and review services read.
 *
 * <p>Review scheduling is entirely about elapsed days, so "now" is an input to the domain rather
 * than an ambient fact. Injecting it lets tests advance time instead of sleeping through it.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
