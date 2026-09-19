PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS problems (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    frontend_id TEXT,
    title TEXT NOT NULL,
    title_slug TEXT NOT NULL UNIQUE,
    difficulty TEXT NOT NULL,
    paid_only INTEGER NOT NULL DEFAULT 0,
    status TEXT,
    acceptance_rate REAL,
    statement_html TEXT,
    sample_test_case TEXT,
    hints_json TEXT NOT NULL DEFAULT '[]',
    topic_tags_json TEXT NOT NULL DEFAULT '[]',
    code_snippets_json TEXT NOT NULL DEFAULT '[]',
    source TEXT NOT NULL,
    synced_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_problems_difficulty ON problems(difficulty);
CREATE INDEX IF NOT EXISTS idx_problems_title ON problems(title);

CREATE TABLE IF NOT EXISTS practice_sessions (
    id TEXT PRIMARY KEY,
    title_slug TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('ACTIVE', 'COMPLETED')),
    started_at TEXT NOT NULL,
    completed_at TEXT,
    target_minutes INTEGER,
    notes TEXT,
    max_hint_level INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY(title_slug) REFERENCES problems(title_slug)
);

CREATE INDEX IF NOT EXISTS idx_sessions_title_slug ON practice_sessions(title_slug);
CREATE INDEX IF NOT EXISTS idx_sessions_started_at ON practice_sessions(started_at);

CREATE TABLE IF NOT EXISTS attempts (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    language TEXT NOT NULL,
    code TEXT NOT NULL,
    verdict TEXT NOT NULL,
    runtime_ms INTEGER,
    memory_kb INTEGER,
    time_complexity TEXT,
    space_complexity TEXT,
    notes TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY(session_id) REFERENCES practice_sessions(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_attempts_session_id ON attempts(session_id);
CREATE INDEX IF NOT EXISTS idx_attempts_created_at ON attempts(created_at);

-- SM-2 review state, one row per problem that has been practised at least once.
CREATE TABLE IF NOT EXISTS review_schedule (
    title_slug TEXT PRIMARY KEY,
    easiness_factor REAL NOT NULL DEFAULT 2.5,
    interval_days INTEGER NOT NULL DEFAULT 0,
    repetitions INTEGER NOT NULL DEFAULT 0,
    lapses INTEGER NOT NULL DEFAULT 0,
    last_grade INTEGER NOT NULL DEFAULT 0,
    last_reviewed_at TEXT,
    due_at TEXT NOT NULL,
    FOREIGN KEY(title_slug) REFERENCES problems(title_slug)
);

CREATE INDEX IF NOT EXISTS idx_review_schedule_due_at ON review_schedule(due_at);
