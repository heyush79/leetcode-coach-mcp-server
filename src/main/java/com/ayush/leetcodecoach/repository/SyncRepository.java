package com.ayush.leetcodecoach.repository;

import com.ayush.leetcodecoach.domain.SyncRun;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SyncRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<SyncRun> rowMapper = this::mapRun;

    public SyncRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void start(SyncRun run) {
        jdbcTemplate.update(
                "INSERT INTO sync_runs (id, started_at, status) VALUES (?, ?, ?)",
                run.id(),
                run.startedAt().toString(),
                run.status());
    }

    public void finish(SyncRun run) {
        jdbcTemplate.update("""
                        UPDATE sync_runs SET
                            finished_at = ?, status = ?, submissions_seen = ?, submissions_imported = ?,
                            sessions_created = ?, sessions_updated = ?, problems_added = ?, message = ?
                        WHERE id = ?
                        """,
                run.finishedAt() == null ? null : run.finishedAt().toString(),
                run.status(),
                run.submissionsSeen(),
                run.submissionsImported(),
                run.sessionsCreated(),
                run.sessionsUpdated(),
                run.problemsAdded(),
                run.message(),
                run.id());
    }

    public Optional<SyncRun> lastRun() {
        return jdbcTemplate.query(
                "SELECT * FROM sync_runs ORDER BY started_at DESC LIMIT 1",
                rowMapper).stream().findFirst();
    }

    private SyncRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        String finishedAt = rs.getString("finished_at");
        return new SyncRun(
                rs.getString("id"),
                Instant.parse(rs.getString("started_at")),
                finishedAt == null ? null : Instant.parse(finishedAt),
                rs.getString("status"),
                rs.getInt("submissions_seen"),
                rs.getInt("submissions_imported"),
                rs.getInt("sessions_created"),
                rs.getInt("sessions_updated"),
                rs.getInt("problems_added"),
                rs.getString("message"));
    }
}
