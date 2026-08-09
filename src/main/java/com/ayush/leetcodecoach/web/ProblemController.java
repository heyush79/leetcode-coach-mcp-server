package com.ayush.leetcodecoach.web;

import com.ayush.leetcodecoach.domain.Problem;
import com.ayush.leetcodecoach.service.ProblemCatalogService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/problems")
public class ProblemController {

    private final ProblemCatalogService catalogService;

    public ProblemController(ProblemCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public List<Problem> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String difficulty,
            @RequestParam(required = false) String topic,
            @RequestParam(defaultValue = "10") int limit) {
        return catalogService.searchProblems(keyword, difficulty, topic, limit);
    }

    @GetMapping("/{titleSlug}")
    public Problem get(
            @PathVariable String titleSlug,
            @RequestParam(defaultValue = "false") boolean refresh) {
        return catalogService.getProblem(titleSlug, refresh);
    }
}
