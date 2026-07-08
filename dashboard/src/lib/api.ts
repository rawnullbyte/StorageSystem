/**
 * REST API client for StorageSystem backend.
 * Configurable via VITE_API_BASE_URL env var (defaults to http://localhost:8000).
 */

const BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8000";

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const url = `${BASE_URL}${path}`;
  const res = await fetch(url, {
    headers: { "Content-Type": "application/json", ...options?.headers },
    ...options,
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({ error: res.statusText }));
    throw new Error(body.error || `HTTP ${res.status}`);
  }
  return res.json();
}

// --- Storage Layers ---

export interface StorageLayer {
  id: number;
  name: string;
  description: string | null;
  created_at: string | null;
}

export async function getLayers(): Promise<StorageLayer[]> {
  return request("/api/layers");
}

export async function createLayer(name: string, description?: string): Promise<StorageLayer> {
  return request("/api/layers", {
    method: "POST",
    body: JSON.stringify({ name, description }),
  });
}

// --- Containers ---

export interface Container {
  id: string; // UUID
  display_name: string;
  storage_layer_id: number;
  created_at: string | null;
  updated_at: string | null;
}

export async function getContainers(layerId?: number): Promise<Container[]> {
  const qs = layerId ? `?layer_id=${layerId}` : "";
  return request(`/api/containers${qs}`);
}

export async function createContainer(
  display_name: string,
  storage_layer_id: number,
  id?: string
): Promise<Container> {
  return request("/api/containers", {
    method: "POST",
    body: JSON.stringify({ display_name, storage_layer_id, id }),
  });
}

// --- Bags ---

export interface BagWithDetails {
  bag_id: number;
  container_id: string;
  lcsc_part_number: string;
  mfg_part_number: string;
  initial_quantity: number;
  current_quantity: number;
  order_number: string | null;
  package_bill_no: string | null;
  scanned_at: string | null;
  updated_at: string | null;
  description: string | null;
  manufacturer: string | null;
  package_type: string | null;
  datasheet_url: string | null;
  container_display_name: string;
  layer_name: string;
  layer_id: number;
}

export async function getBags(): Promise<BagWithDetails[]> {
  return request("/api/components");
}

export interface AddBagPayload {
  container_id: string;
  lcsc_part_number: string;
  mfg_part_number: string;
  quantity: number;
  order_number?: string;
  package_bill_no?: string;
}

export interface AddBagResponse {
  created: boolean;
  current_quantity: number;
  message: string;
}

export async function addBag(payload: AddBagPayload): Promise<AddBagResponse> {
  return request("/api/components", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export interface UpdateQuantityPayload {
  container_id: string;
  lcsc_part_number: string;
  quantity: number;
}

export async function updateQuantity(payload: UpdateQuantityPayload): Promise<void> {
  await request("/api/components/quantity", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

// --- Search ---

export interface SearchResult {
  matched_containers: string[];
  matched_part_numbers: string[];
}

export async function search(term: string): Promise<SearchResult> {
  return request("/api/search", {
    method: "POST",
    body: JSON.stringify({ term }),
  });
}
