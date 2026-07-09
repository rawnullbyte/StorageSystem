-- StorageSystem: Final Schema (all migrations merged into one)

CREATE TABLE IF NOT EXISTS storage_layers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    description TEXT,
    created_at TEXT DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
);

CREATE TABLE IF NOT EXISTS containers (
    id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    storage_layer_id INTEGER NOT NULL REFERENCES storage_layers(id) ON DELETE CASCADE,
    created_at TEXT DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    updated_at TEXT DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_containers_layer ON containers(storage_layer_id);

CREATE TABLE IF NOT EXISTS lcsc_parts (
    lcsc_part_number TEXT PRIMARY KEY,
    mfg_part_number TEXT NOT NULL,
    description TEXT,
    manufacturer TEXT,
    package_type TEXT,
    datasheet_url TEXT,
    price_usd_json TEXT,
    created_at TEXT DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
);

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

-- Tags
CREATE TABLE IF NOT EXISTS container_tags (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    container_id TEXT NOT NULL REFERENCES containers(id) ON DELETE CASCADE,
    tag TEXT NOT NULL COLLATE NOCASE,
    UNIQUE(container_id, tag)
);
CREATE INDEX IF NOT EXISTS idx_container_tags_tag ON container_tags(tag);

CREATE TABLE IF NOT EXISTS component_tags (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    bag_id INTEGER NOT NULL REFERENCES component_bags(id) ON DELETE CASCADE,
    tag TEXT NOT NULL COLLATE NOCASE,
    UNIQUE(bag_id, tag)
);
CREATE INDEX IF NOT EXISTS idx_component_tags_tag ON component_tags(tag);
