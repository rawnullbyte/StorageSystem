-- StorageSystem: Schema v2 — Tags + container dedup
-- SQLite dialect.

-- Tags for containers (applies to all components in that container)
CREATE TABLE IF NOT EXISTS container_tags (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    container_id TEXT NOT NULL REFERENCES containers(id) ON DELETE CASCADE,
    tag TEXT NOT NULL COLLATE NOCASE,
    UNIQUE(container_id, tag)
);
CREATE INDEX IF NOT EXISTS idx_container_tags_tag ON container_tags(tag);

-- Tags for individual component bags
CREATE TABLE IF NOT EXISTS component_tags (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    bag_id INTEGER NOT NULL REFERENCES component_bags(id) ON DELETE CASCADE,
    tag TEXT NOT NULL COLLATE NOCASE,
    UNIQUE(bag_id, tag)
);
CREATE INDEX IF NOT EXISTS idx_component_tags_tag ON component_tags(tag);
