package com.ayush.leetcodecoach.repository;

import com.ayush.leetcodecoach.domain.CodeSnippet;
import com.ayush.leetcodecoach.domain.Difficulty;
import com.ayush.leetcodecoach.domain.Problem;
import com.ayush.leetcodecoach.domain.TopicTag;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ProblemRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<List<TopicTag>> TOPIC_LIST = new TypeReference<>() { };
    private static final TypeReference<List<CodeSnippet>> SNIPPET_LIST = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<Problem> rowMapper = this::mapProblem;

    public ProblemRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM problems", Long.class);
        return count == null ? 0 : count;
    }

    public void upsert(Problem problem) {
        String sql = """
                INSERT INTO problems (
                    frontend_id, title, title_slug, difficulty, paid_only, status, acceptance_rate,
                    statement_html, sample_test_case, hints_json, topic_tags_json, code_snippets_json,
                    source, synced_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(title_slug) DO UPDATE SET
                    frontend_id = COALESCE(excluded.frontend_id, problems.frontend_id),
                    title = excluded.title,
                    difficulty = excluded.difficulty,
                    paid_only = excluded.paid_only,
                    status = COALESCE(excluded.status, problems.status),
                    acceptance_rate = COALESCE(excluded.acceptance_rate, problems.acceptance_rate),
                    statement_html = COALESCE(excluded.statement_html, problems.statement_html),
                    sample_test_case = COALESCE(excluded.sample_test_case, problems.sample_test_case),
                    hints_json = CASE WHEN excluded.hints_json = '[]' THEN problems.hints_json ELSE excluded.hints_json END,
                    topic_tags_json = CASE WHEN excluded.topic_tags_json = '[]' THEN problems.topic_tags_json ELSE excluded.topic_tags_json END,
                    code_snippets_json = CASE WHEN excluded.code_snippets_json = '[]' THEN problems.code_snippets_json ELSE excluded.code_snippets_json END,
                    source = excluded.source,
                    synced_at = excluded.synced_at
                """;
        jdbcTemplate.update(sql,
                problem.frontendId(),
                problem.title(),
                problem.titleSlug(),
                problem.difficulty().name(),
                problem.paidOnly() ? 1 : 0,
                problem.status(),
                problem.acceptanceRate(),
                problem.statementHtml(),
                problem.sampleTestCase(),
                writeJson(problem.hints()),
                writeJson(problem.topicTags()),
                writeJson(problem.codeSnippets()),
                problem.source(),
                problem.syncedAt().toString());
    }

    public Optional<Problem> findBySlug(String titleSlug) {
        return jdbcTemplate.query(
                "SELECT * FROM problems WHERE title_slug = ?",
                rowMapper,
                titleSlug).stream().findFirst();
    }

    public List<Problem> search(String keyword, String difficulty, String topic, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM problems WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (lower(title) LIKE ? OR lower(title_slug) LIKE ?)");
            String pattern = "%" + keyword.trim().toLowerCase() + "%";
            args.add(pattern);
            args.add(pattern);
        }
        if (difficulty != null && !difficulty.isBlank()) {
            sql.append(" AND difficulty = ?");
            args.add(Difficulty.from(difficulty).name());
        }
        if (topic != null && !topic.isBlank()) {
            sql.append(" AND lower(topic_tags_json) LIKE ?");
            args.add("%" + topic.trim().toLowerCase() + "%");
        }
        sql.append(" ORDER BY CAST(frontend_id AS INTEGER), title LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 50)));
        return jdbcTemplate.query(sql.toString(), rowMapper, args.toArray());
    }

    public Optional<Problem> recommendUnattempted(String difficulty, String topic) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.* FROM problems p
                WHERE p.title_slug NOT IN (
                    SELECT DISTINCT s.title_slug FROM practice_sessions s
                )
                """);
        List<Object> args = new ArrayList<>();
        if (difficulty != null && !difficulty.isBlank()) {
            sql.append(" AND p.difficulty = ?");
            args.add(Difficulty.from(difficulty).name());
        }
        if (topic != null && !topic.isBlank()) {
            sql.append(" AND lower(p.topic_tags_json) LIKE ?");
            args.add("%" + topic.trim().toLowerCase() + "%");
        }
        sql.append(" ORDER BY RANDOM() LIMIT 1");
        return jdbcTemplate.query(sql.toString(), rowMapper, args.toArray()).stream().findFirst();
    }

    private Problem mapProblem(ResultSet rs, int rowNum) throws SQLException {
        return new Problem(
                rs.getLong("id"),
                rs.getString("frontend_id"),
                rs.getString("title"),
                rs.getString("title_slug"),
                Difficulty.from(rs.getString("difficulty")),
                rs.getInt("paid_only") == 1,
                rs.getString("status"),
                getNullableDouble(rs, "acceptance_rate"),
                rs.getString("statement_html"),
                rs.getString("sample_test_case"),
                readJson(rs.getString("hints_json"), STRING_LIST),
                readJson(rs.getString("topic_tags_json"), TOPIC_LIST),
                readJson(rs.getString("code_snippets_json"), SNIPPET_LIST),
                rs.getString("source"),
                Instant.parse(rs.getString("synced_at")));
    }

    private Double getNullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        }
        catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize database JSON", ex);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json == null || json.isBlank() ? "[]" : json, type);
        }
        catch (Exception ex) {
            throw new IllegalStateException("Unable to deserialize database JSON", ex);
        }
    }
}
