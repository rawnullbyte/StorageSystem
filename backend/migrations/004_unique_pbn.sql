-- StorageSystem v4: Change unique constraint from (container_id, lcsc_part_number)
-- to (package_bill_no) — the bag ID (pbn) is truly unique, but the same
-- LCSC part can be in multiple containers with different bags.
-- SQLite doesn't support ALTER TABLE DROP CONSTRAINT, so we recreate.

-- Rename old table, create new one with correct constraint, copy data
ALTER TABLE component_bags RENAME TO component_bags_old;

CREATE TABLE IF NOT EXISTS component_bags (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    container_id TEXT NOT NULL REFERENCES containers(id) ON DELETE CASCADE,
    lcsc_part_number TEXT NOT NULL REFERENCES lcsc_parts(lcsc_part_number),
    initial_quantity INTEGER NOT NULL CHECK (initial_quantity >= 0),
    current_quantity INTEGER NOT NULL CHECK (current_quantity >= 0),
    order_number TEXT,
    package_bill_no TEXT UNIQUE,
    manufacturer_code TEXT,
    carton_count TEXT,
    packing_date TEXT,
    warehouse_code TEXT,
    scanned_at TEXT DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    updated_at TEXT DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_bags_container ON component_bags(container_id);
CREATE INDEX IF NOT EXISTS idx_bags_part ON component_bags(lcsc_part_number);
CREATE INDEX IF NOT EXISTS idx_bags_order ON component_bags(order_number);

INSERT INTO component_bags SELECT * FROM component_bags_old;

DROP TABLE component_bags_old;
