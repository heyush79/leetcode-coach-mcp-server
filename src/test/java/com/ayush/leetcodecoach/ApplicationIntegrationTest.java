package com.ayush.leetcodecoach;

import static org.assertj.core.api.Assertions.assertThat;

import com.ayush.leetcodecoach.domain.PracticeSession;
import com.ayush.leetcodecoach.service.PracticeService;
import com.ayush.leetcodecoach.service.ProblemCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "leetcode.remote-enabled=false",
        "spring.datasource.url=jdbc:sqlite:file:leetcode-coach-integration?mode=memory&cache=shared"
})
class ApplicationIntegrationTest {

    @Autowired
    private ProblemCatalogService catalogService;

    @Autowired
    private PracticeService practiceService;

    @Test
    void runsOfflinePracticeWorkflowAgainstSQLite() {
        assertThat(catalogService.count()).isGreaterThanOrEqualTo(6);
        assertThat(catalogService.searchProblems(null, "MEDIUM", null, 10).stream()
                .map(problem -> problem.titleSlug())
                .toList())
                .contains("number-of-islands");

        PracticeSession session = practiceService.startPractice("number-of-islands", 30);
        practiceService.recordAttempt(
                session.id(),
                "cpp",
                "int numIslands(vector<vector<char>>& grid) { return 1; }",
                "ACCEPTED",
                12L,
                2048L,
                "O(n*m)",
                "O(n*m)",
                "Mark nodes visited when enqueuing them");
        var completion = practiceService.completePractice(session.id(), "Review BFS visited timing");

        assertThat(completion.session().status()).isEqualTo("COMPLETED");
        assertThat(completion.review()).isNotNull();
        assertThat(completion.review().nextReviewInDays()).isGreaterThanOrEqualTo(1);
        assertThat(practiceService.getSessionContext(session.id()).attempts()).hasSize(1);
        assertThat(practiceService.getProgressStats().completedSessions()).isGreaterThanOrEqualTo(1);
        assertThat(practiceService.getProgressStats().acceptedAttempts()).isGreaterThanOrEqualTo(1);
    }
}
