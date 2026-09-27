CREATE TABLE IF NOT EXISTS profile_entries (
    id TEXT NOT NULL PRIMARY KEY,
    section TEXT NOT NULL,
    entry_key TEXT NOT NULL,
    value TEXT NOT NULL,
    evidence TEXT NOT NULL DEFAULT '',
    confidence REAL NOT NULL DEFAULT 0.5,
    source_session_id TEXT,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    superseded_by TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS index_profile_entries_section_status
    ON profile_entries(section, status);
