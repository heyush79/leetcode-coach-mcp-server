package com.ayush.leetcodecoach.config;

import com.ayush.leetcodecoach.domain.CodeSnippet;
import com.ayush.leetcodecoach.domain.Difficulty;
import com.ayush.leetcodecoach.domain.Problem;
import com.ayush.leetcodecoach.domain.TopicTag;
import com.ayush.leetcodecoach.service.ProblemCatalogService;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(SchemaMigrations.ORDER + 1)
public class SeedDataLoader implements ApplicationRunner {

    private final ProblemCatalogService catalogService;

    public SeedDataLoader(ProblemCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (catalogService.count() > 0) {
            return;
        }
        seed("1", "Two Sum", "two-sum", Difficulty.EASY,
                "Given an integer array and a target, return the indices of two distinct values whose sum equals the target.",
                List.of("Can you remember values already visited?", "Store the complement needed for each value."),
                List.of(new TopicTag("Array", "array"), new TopicTag("Hash Table", "hash-table")));
        seed("20", "Valid Parentheses", "valid-parentheses", Difficulty.EASY,
                "Determine whether brackets in a string are correctly opened, matched, and closed.",
                List.of("The most recently opened bracket must close first.", "A stack models that ordering directly."),
                List.of(new TopicTag("String", "string"), new TopicTag("Stack", "stack")));
        seed("704", "Binary Search", "binary-search", Difficulty.EASY,
                "Find a target in a sorted integer array using logarithmic search.",
                List.of("Keep a closed interval containing every possible answer.", "After comparing the middle value, discard the impossible half."),
                List.of(new TopicTag("Array", "array"), new TopicTag("Binary Search", "binary-search")));
        seed("200", "Number of Islands", "number-of-islands", Difficulty.MEDIUM,
                "Count connected groups of land cells in a two-dimensional grid.",
                List.of("Each unvisited land cell begins a new component.", "Flood-fill every cell belonging to that component."),
                List.of(new TopicTag("Array", "array"), new TopicTag("Depth-First Search", "depth-first-search"), new TopicTag("Breadth-First Search", "breadth-first-search")));
        seed("322", "Coin Change", "coin-change", Difficulty.MEDIUM,
                "Compute the minimum number of coins needed to form a target amount, or report that it is impossible.",
                List.of("Define the best answer for every smaller amount.", "For each amount, try extending a previously solved state by one coin."),
                List.of(new TopicTag("Array", "array"), new TopicTag("Dynamic Programming", "dynamic-programming")));
        seed("146", "LRU Cache", "lru-cache", Difficulty.MEDIUM,
                "Design a fixed-capacity cache supporting constant-time reads, updates, and least-recently-used eviction.",
                List.of("One structure must locate keys in constant time.", "Another must maintain recency while supporting constant-time removal and insertion."),
                List.of(new TopicTag("Hash Table", "hash-table"), new TopicTag("Linked List", "linked-list"), new TopicTag("Design", "design")));
    }

    private void seed(
            String frontendId,
            String title,
            String slug,
            Difficulty difficulty,
            String statement,
            List<String> hints,
            List<TopicTag> tags) {
        catalogService.saveSeed(new Problem(
                null,
                frontendId,
                title,
                slug,
                difficulty,
                false,
                null,
                null,
                "<p>" + statement + "</p>",
                null,
                hints,
                tags,
                List.of(new CodeSnippet("C++", "cpp", "class Solution {\npublic:\n    // Implement here\n};")),
                "LOCAL_SEED",
                Instant.now()));
    }
}
