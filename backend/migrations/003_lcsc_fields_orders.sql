-- StorageSystem v3: Additional LCSC fields + order_number index
ALTER TABLE component_bags ADD COLUMN manufacturer_code TEXT;
ALTER TABLE component_bags ADD COLUMN carton_count TEXT;
ALTER TABLE component_bags ADD COLUMN packing_date TEXT;
ALTER TABLE component_bags ADD COLUMN warehouse_code TEXT;
CREATE INDEX IF NOT EXISTS idx_bags_order ON component_bags(order_number);
