package com.ayush.leetcodecoach.repository;

import com.ayush.leetcodecoach.domain.Attempt;
import com.ayush.leetcodecoach.domain.PracticeSession;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PracticeRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<PracticeSession> sessionMapper = this::mapSession;
    private final RowMapper<Attempt> attemptMapper = this::mapAttempt;

    public PracticeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertSession(PracticeSession session) {
        jdbcTemplate.update("""
                        INSERT INTO practice_sessions
                        (id, title_slug, status, started_at, completed_at, target_minutes, notes,
                         max_hint_level, source)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                session.id(),
                session.titleSlug(),
                session.status(),
                session.startedAt().toString(),
                session.completedAt() == null ? null : session.completedAt().toString(),
                session.targetMinutes(),
                session.notes(),
                session.maxHintLevel(),
                session.source());
    }

    /** Every session for a problem, oldest first. Window logic is done in Java, not SQL. */
    public List<PracticeSession> findSessionsForProblem(String titleSlug) {
        return jdbcTemplate.query(
                "SELECT * FROM practice_sessions WHERE title_slug = ? ORDER BY started_at",
                sessionMapper,
                titleSlug);
    }

    /** Widens a synced session's span when a submission joins it on either side. */
    public void extendSession(String sessionId, Instant startedAt, Instant completedAt) {
        jdbcTemplate.update(
                "UPDATE practice_sessions SET started_at = ?, completed_at = ? WHERE id = ?",
                startedAt.toString(),
                completedAt.toString(),
                sessionId);
    }

    /** Records the deepest hint the user has asked for; hint level never decreases. */
    public void recordHintLevel(String sessionId, int level) {
        jdbcTemplate.update(
                "UPDATE practice_sessions SET max_hint_level = MAX(max_hint_level, ?) WHERE id = ?",
                level,
                sessionId);
    }

    public Optional<PracticeSession> findSession(String sessionId) {
        return jdbcTemplate.query(
                "SELECT * FROM practice_sessions WHERE id = ?",
                sessionMapper,
                sessionId).stream().findFirst();
    }

    public void completeSession(String sessionId, String notes, Instant completedAt) {
        jdbcTemplate.update("""
                UPDATE practice_sessions
                SET status = 'COMPLETED', completed_at = ?, notes = ?
                WHERE id = ?
                """, completedAt.toString(), notes, sessionId);
    }

    public void insertAttempt(Attempt attempt) {
        jdbcTemplate.update("""
                        INSERT INTO attempts (
                            id, session_id, language, code, verdict, runtime_ms, memory_kb,
                            time_complexity, space_complexity, notes, created_at,
                            source, leetcode_submission_id
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                attempt.id(),
                attempt.sessionId(),
                attempt.language(),
                attempt.code(),
                attempt.verdict(),
                attempt.runtimeMs(),
                attempt.memoryKb(),
                attempt.timeComplexity(),
                attempt.spaceComplexity(),
                attempt.notes(),
                attempt.createdAt().toString(),
                attempt.source(),
                attempt.leetcodeSubmissionId());
    }

    public boolean attemptExistsForSubmission(String leetcodeSubmissionId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attempts WHERE leetcode_submission_id = ?",
                Long.class,
                leetcodeSubmissionId);
        return count != null && count > 0;
    }

    public long countSyncedAttempts() {
        return nullableLong("SELECT COUNT(*) FROM attempts WHERE source = 'LEETCODE'");
    }

    public List<Attempt> findAttempts(String sessionId) {
        return jdbcTemplate.query(
                "SELECT * FROM attempts WHERE session_id = ? ORDER BY created_at",
                attemptMapper,
                sessionId);
    }

    public long completedSessionCount() {
        return nullableLong("SELECT COUNT(*) FROM practice_sessions WHERE status = 'COMPLETED'");
    }

    public long totalAttemptCount() {
        return nullableLong("SELECT COUNT(*) FROM attempts");
    }

    public long acceptedAttemptCount() {
        return nullableLong("SELECT COUNT(*) FROM attempts WHERE upper(verdict) = 'ACCEPTED'");
    }

    public List<LocalDate> completionDates() {
        return jdbcTemplate.query(
                "SELECT DISTINCT substr(completed_at, 1, 10) AS completed_date "
                        + "FROM practice_sessions WHERE completed_at IS NOT NULL ORDER BY completed_date DESC",
                (rs, rowNum) -> LocalDate.parse(rs.getString("completed_date")));
    }

    public Map<String, Long> completedByDifficulty() {
        return jdbcTemplate.query("""
                        SELECT p.difficulty, COUNT(*) AS problem_count
                        FROM practice_sessions s
                        JOIN problems p ON p.title_slug = s.title_slug
                        WHERE s.status = 'COMPLETED'
                        GROUP BY p.difficulty
                        """,
                rs -> {
                    Map<String, Long> values = new java.util.LinkedHashMap<>();
                    while (rs.next()) {
                        values.put(rs.getString("difficulty"), rs.getLong("problem_count"));
                    }
                    return values;
                });
    }

    public List<PracticeSession> recentSessions(int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM practice_sessions ORDER BY started_at DESC LIMIT ?",
                sessionMapper,
                Math.max(1, Math.min(limit, 50)));
    }

    private long nullableLong(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private PracticeSession mapSession(ResultSet rs, int rowNum) throws SQLException {
        String completedAt = rs.getString("completed_at");
        return new PracticeSession(
                rs.getString("id"),
                rs.getString("title_slug"),
                rs.getString("status"),
                Instant.parse(rs.getString("started_at")),
                completedAt == null ? null : Instant.parse(completedAt),
                getNullableInteger(rs, "target_minutes"),
                rs.getString("notes"),
                rs.getInt("max_hint_level"),
                rs.getString("source"));
    }

    private Attempt mapAttempt(ResultSet rs, int rowNum) throws SQLException {
        return new Attempt(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("language"),
                rs.getString("code"),
                rs.getString("verdict"),
                getNullableLong(rs, "runtime_ms"),
                getNullableLong(rs, "memory_kb"),
                rs.getString("time_complexity"),
                rs.getString("space_complexity"),
                rs.getString("notes"),
                Instant.parse(rs.getString("created_at")),
                rs.getString("source"),
                rs.getString("leetcode_submission_id"));
    }

    private Integer getNullableInteger(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof Number number ? number.intValue() : null;
    }

    private Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
