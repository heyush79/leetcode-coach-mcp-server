package com.ayush.leetcodecoach;

import com.ayush.leetcodecoach.config.LeetCodeProperties;
import com.ayush.leetcodecoach.config.McpAccessProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({LeetCodeProperties.class, McpAccessProperties.class})
public class LeetCodeCoachApplication {

    public static void main(String[] args) {
        SpringApplication.run(LeetCodeCoachApplication.class, args);
    }
}
