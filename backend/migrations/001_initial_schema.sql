-- StorageSystem: Initial Database Schema
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Storage layers (shelves, drawers, racks)
CREATE TABLE IF NOT EXISTS storage_layers (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Physical containers (bins, drawers) with QR-identifiable UUIDs
CREATE TABLE IF NOT EXISTS containers (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    display_name VARCHAR(255) NOT NULL,
    storage_layer_id INTEGER NOT NULL REFERENCES storage_layers(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Index for fast layer-based lookups
CREATE INDEX IF NOT EXISTS idx_containers_layer ON containers(storage_layer_id);

-- LCSC part metadata cache
CREATE TABLE IF NOT EXISTS lcsc_parts (
    lcsc_part_number VARCHAR(64) PRIMARY KEY,
    mfg_part_number VARCHAR(255) NOT NULL,
    description TEXT,
    manufacturer VARCHAR(255),
    package_type VARCHAR(64),
    datasheet_url TEXT,
    price_usd_json JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Component bags (1 bag = 1 part type in 1 container)
CREATE TABLE IF NOT EXISTS component_bags (
    id SERIAL PRIMARY KEY,
    container_id UUID NOT NULL REFERENCES containers(id) ON DELETE CASCADE,
    lcsc_part_number VARCHAR(64) NOT NULL REFERENCES lcsc_parts(lcsc_part_number),
    initial_quantity INTEGER NOT NULL CHECK (initial_quantity >= 0),
    current_quantity INTEGER NOT NULL CHECK (current_quantity >= 0),
    order_number VARCHAR(128),
    package_bill_no VARCHAR(128),
    scanned_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_bag_in_container UNIQUE (container_id, lcsc_part_number)
);

-- Indexes for bag queries
CREATE INDEX IF NOT EXISTS idx_bags_container ON component_bags(container_id);
CREATE INDEX IF NOT EXISTS idx_bags_part ON component_bags(lcsc_part_number);
