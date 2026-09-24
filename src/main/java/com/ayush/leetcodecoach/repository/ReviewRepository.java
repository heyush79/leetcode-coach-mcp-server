package com.ayush.leetcodecoach.repository;

import com.ayush.leetcodecoach.domain.ReviewSchedule;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ReviewSchedule> rowMapper = this::mapSchedule;

    public ReviewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(ReviewSchedule schedule) {
        jdbcTemplate.update("""
                        INSERT INTO review_schedule (
                            title_slug, easiness_factor, interval_days, repetitions,
                            lapses, last_grade, last_reviewed_at, due_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(title_slug) DO UPDATE SET
                            easiness_factor = excluded.easiness_factor,
                            interval_days = excluded.interval_days,
                            repetitions = excluded.repetitions,
                            lapses = excluded.lapses,
                            last_grade = excluded.last_grade,
                            last_reviewed_at = excluded.last_reviewed_at,
                            due_at = excluded.due_at
                        """,
                schedule.titleSlug(),
                schedule.easinessFactor(),
                schedule.intervalDays(),
                schedule.repetitions(),
                schedule.lapses(),
                schedule.lastGrade(),
                schedule.lastReviewedAt() == null ? null : schedule.lastReviewedAt().toString(),
                schedule.dueAt().toString());
    }

    public Optional<ReviewSchedule> find(String titleSlug) {
        return jdbcTemplate.query(
                "SELECT * FROM review_schedule WHERE title_slug = ?",
                rowMapper,
                titleSlug).stream().findFirst();
    }

    /** Schedules due at or before {@code now}, most overdue first. */
    public List<ReviewSchedule> findDue(Instant now, int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM review_schedule WHERE due_at <= ? ORDER BY due_at LIMIT ?",
                rowMapper,
                now.toString(),
                Math.max(1, Math.min(limit, 50)));
    }

    public List<ReviewSchedule> findAll() {
        return jdbcTemplate.query("SELECT * FROM review_schedule ORDER BY due_at", rowMapper);
    }

    public void delete(String titleSlug) {
        jdbcTemplate.update("DELETE FROM review_schedule WHERE title_slug = ?", titleSlug);
    }

    public long countDue(Instant now) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_schedule WHERE due_at <= ?", Long.class, now.toString());
        return value == null ? 0 : value;
    }

    private ReviewSchedule mapSchedule(ResultSet rs, int rowNum) throws SQLException {
        String lastReviewedAt = rs.getString("last_reviewed_at");
        return new ReviewSchedule(
                rs.getString("title_slug"),
                rs.getDouble("easiness_factor"),
                rs.getInt("interval_days"),
                rs.getInt("repetitions"),
                rs.getInt("lapses"),
                rs.getInt("last_grade"),
                lastReviewedAt == null ? null : Instant.parse(lastReviewedAt),
                Instant.parse(rs.getString("due_at")));
    }
}
