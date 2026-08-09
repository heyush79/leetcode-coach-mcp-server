package com.ayush.leetcodecoach.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ayush.leetcodecoach.domain.Difficulty;
import com.ayush.leetcodecoach.domain.PracticeSession;
import com.ayush.leetcodecoach.domain.Problem;
import com.ayush.leetcodecoach.domain.TopicTag;
import com.ayush.leetcodecoach.repository.PracticeRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PracticeServiceTest {

    @Mock
    private ProblemCatalogService catalogService;

    @Mock
    private PracticeRepository practiceRepository;

    @Test
    void startsPersistedPracticeSession() {
        Problem problem = problem(List.of("Use a map"));
        when(catalogService.getProblem("two-sum", false)).thenReturn(problem);
        PracticeService service = new PracticeService(catalogService, practiceRepository);

        PracticeSession session = service.startPractice("two-sum", 45);

        assertThat(session.titleSlug()).isEqualTo("two-sum");
        assertThat(session.status()).isEqualTo("ACTIVE");
        assertThat(session.targetMinutes()).isEqualTo(45);
        ArgumentCaptor<PracticeSession> captor = ArgumentCaptor.forClass(PracticeSession.class);
        verify(practiceRepository).insertSession(captor.capture());
        assertThat(captor.getValue().id()).isEqualTo(session.id());
    }

    @Test
    void returnsProgressiveHintWithoutSolutionCode() {
        Problem problem = problem(List.of("Use a map", "Search for the complement"));
        PracticeSession session = new PracticeSession(
                "session-1", "two-sum", "ACTIVE", Instant.now(), null, 30, null);
        when(practiceRepository.findSession("session-1")).thenReturn(Optional.of(session));
        when(practiceRepository.findAttempts("session-1")).thenReturn(List.of());
        when(catalogService.getProblem("two-sum", false)).thenReturn(problem);
        PracticeService service = new PracticeService(catalogService, practiceRepository);

        var response = service.getHint("session-1", 2);

        assertThat(response.hints()).containsExactly("Use a map", "Search for the complement");
        assertThat(response.guardrail()).contains("avoid complete code");
    }

    @Test
    void calculatesStreakFromTodayBackwards() {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        when(practiceRepository.completionDates()).thenReturn(List.of(today, today.minusDays(1), today.minusDays(2)));
        when(practiceRepository.completedByDifficulty()).thenReturn(Map.of("EASY", 2L));
        when(practiceRepository.completedSessionCount()).thenReturn(3L);
        when(practiceRepository.acceptedAttemptCount()).thenReturn(2L);
        when(practiceRepository.totalAttemptCount()).thenReturn(4L);
        PracticeService service = new PracticeService(catalogService, practiceRepository);

        var stats = service.getProgressStats();

        assertThat(stats.currentStreak()).isEqualTo(3);
        assertThat(stats.completedByDifficulty()).containsEntry("EASY", 2L).containsEntry("HARD", 0L);
    }

    private Problem problem(List<String> hints) {
        return new Problem(
                1L,
                "1",
                "Two Sum",
                "two-sum",
                Difficulty.EASY,
                false,
                null,
                50.0,
                "<p>Find two values.</p>",
                null,
                hints,
                List.of(new TopicTag("Hash Table", "hash-table")),
                List.of(),
                "TEST",
                Instant.now());
    }
}
