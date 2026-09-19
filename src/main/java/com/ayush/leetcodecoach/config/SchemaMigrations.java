package com.ayush.leetcodecoach.config;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Adds columns that {@code schema.sql} cannot.
 *
 * <p>{@code schema.sql} runs on every start and uses {@code CREATE TABLE IF NOT EXISTS}, which is
 * idempotent but silently skips a table that already exists. A database created before a column was
 * introduced therefore never gains it. SQLite has no {@code ADD COLUMN IF NOT EXISTS}, so each
 * addition is guarded by a {@code PRAGMA table_info} check instead.
 *
 * <p>Runs before {@link SeedDataLoader}. Spring executes {@code spring.sql.init} scripts while the
 * DataSource is initialized, which is before any {@link ApplicationRunner}, so the tables exist by
 * the time this runs.
 *
 * <p>This is deliberately the smallest thing that preserves existing practice history. A service
 * with more than one deployment target belongs on Flyway or Liquibase; a single embedded file does
 * not need a migration framework to add one column.
 */
@Component
@Order(SchemaMigrations.ORDER)
public class SchemaMigrations implements ApplicationRunner {

    /** Ahead of {@link SeedDataLoader}, which reads the tables this may still be altering. */
    public static final int ORDER = 0;

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrations.class);

    private final JdbcTemplate jdbcTemplate;

    public SchemaMigrations(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        addColumnIfMissing("practice_sessions", "max_hint_level", "INTEGER NOT NULL DEFAULT 0");
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        if (columnExists(table, column)) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        log.info("Added missing column {}.{}", table, column);
    }

    private boolean columnExists(String table, String column) {
        List<String> columns = jdbcTemplate.query(
                "PRAGMA table_info(" + table + ")",
                (rs, rowNum) -> rs.getString("name"));
        return columns.stream().anyMatch(column::equalsIgnoreCase);
    }
}
