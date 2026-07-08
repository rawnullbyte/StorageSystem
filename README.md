# StorageSystem — High-Density Inventory Tracking

A complete inventory management system for surface-mount components stored in high-density packaging bags (LCSC Electronics) inside physical containers on storage layers.

## System Architecture

```
┌─────────────────┐      REST + WS      ┌──────────────────┐
│  Android App     │ ◄─────────────────► │  Rust Backend     │
│  (Kotlin/Compose)│     (HTTP/WS)       │  (Axum/Tokio)     │
│  CameraX + ML Kit│                     │  + PostgreSQL     │
└─────────────────┘                      └────────┬─────────┘
                                                  │
                                                  │ SQLx
                                                  ▼
┌─────────────────┐                      ┌──────────────────┐
│  React Dashboard │ ◄─────────────────► │   PostgreSQL      │
│  (TypeScript)    │     REST + WS       │   (via docker)    │
└─────────────────┘                      └──────────────────┘
```

## Components

### 1. Rust Backend (`./backend/`)

- **Framework:** Axum 0.7 with Tokio runtime
- **Database:** PostgreSQL via SQLx (async)
- **WebSocket:** `tokio::sync::broadcast` fans out events to all connected clients
- **LCSC Integration:** SHA-1 signed API client for `ips.lcsc.com` + EasyEDA fallback

**API Endpoints:**

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/layers` | List all storage layers |
| POST | `/api/layers` | Create a layer |
| GET | `/api/containers` | List containers (filter by `?layer_id=N`) |
| POST | `/api/containers` | Register a container |
| PATCH | `/api/containers/:id` | Update container name/layer |
| GET | `/api/bags` | List all component bags with details |
| POST | `/api/bags` | Add bag (idempotent — `ON CONFLICT DO NOTHING`) |
| POST | `/api/bags/quantity` | Manually override quantity |
| POST | `/api/search` | Search containers & parts |
| GET | `/ws` | WebSocket upgrade (broadcast events) |

### 2. Android App (`./android/`)

- **Language:** Kotlin with Jetpack Compose
- **Camera:** CameraX with ML Kit Barcode Scanning
- **Network:** Ktor HTTP + WebSocket client
- **QR Parsing:** Container (`{"cid":"uuid"}`) and LCSC bag (`{pbn=...,pc=...,...}`)

**Three Scan Modes:**

1. **Auto-Import Containers** — Select a layer, scan container QR codes to register them.
2. **Assign Bag to Container** — Scan LCSC bag QR codes, tap to select a target container.
3. **Search & Highlight** — Enter a search term; matching container/bag QR codes are highlighted green.

### 3. Web Dashboard (`./dashboard/`)

- **Stack:** React 18, TypeScript, Tailwind CSS, shadcn/ui primitives
- **State:** Zustand store with real-time WebSocket updates
- **Features:**
  - Table of all component bags with stock levels
  - Adjust Stock button for manual quantity overrides
  - Layer/container sidebar for navigation
  - Live connection status indicator

## Quick Start

### Prerequisites

- Docker & Docker Compose
- Rust 1.76+ (for native backend development)
- Node.js 18+ (for dashboard development)
- Android Studio (for mobile app)

### 1. Database + Backend (Docker)

```bash
# Start PostgreSQL and the Rust backend
docker compose up -d

# The API is now available at http://localhost:8000
# WebSocket at ws://localhost:8000/ws
```

### 2. Backend (Native Development)

```bash
cd backend
cp .env.example .env

# Ensure PostgreSQL is running and update DATABASE_URL in .env
cargo run
```

### 3. Dashboard

```bash
cd dashboard
npm install
npm run dev
# Opens at http://localhost:5173
```

### 4. Android App

Open `./android/` in Android Studio. The app connects to `http://10.0.2.2:8000` by default (Android emulator loopback to host).

To change the backend URL, update `API_BASE_URL` and `WS_URL` in `android/app/build.gradle.kts`.

## Environment Variables

### Backend

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `postgres://storage:storage_pass@localhost:5432/inventory` | PostgreSQL connection string |
| `HOST` | `0.0.0.0` | Bind address |
| `PORT` | `8000` | Listen port |
| `RUST_LOG` | `info` | Log level |
| `LCSC_API_KEY` | *(optional)* | LCSC Open API key |
| `LCSC_API_SECRET` | *(optional)* | LCSC Open API secret |

### Dashboard

| Variable | Default | Description |
|----------|---------|-------------|
| `VITE_API_BASE_URL` | `http://localhost:8000` | Backend REST URL |
| `VITE_WS_URL` | `ws://localhost:8000/ws` | WebSocket URL |

## Database Schema

```
storage_layers (id, name, description, created_at)
containers (id UUID, display_name, storage_layer_id, created_at, updated_at)
lcsc_parts (lcsc_part_number PK, mfg_part_number, description, ...)
component_bags (id, container_id, lcsc_part_number, initial_quantity, current_quantity, ...)
    UNIQUE(container_id, lcsc_part_number)
```

The `component_bags` table has a unique constraint on `(container_id, lcsc_part_number)` to prevent duplicate entries and protect manual stock adjustments from being overwritten by re-scans.

## WebSocket Events

```json
{"type": "BagAdded",       "payload": {"container_id": "...", "lcsc_part_number": "...", "quantity": 100}}
{"type": "QuantityUpdated", "payload": {"container_id": "...", "lcsc_part_number": "...", "new_quantity": 95}}
{"type": "ContainerMoved",  "payload": {"container_id": "...", "new_layer_id": 2}}
```

## Testing Scenarios

1. **Container import:** Select a layer, scan a container QR → appears in dashboard
2. **Bag assignment:** Scan an LCSC bag QR, tap it, select a container → bag appears under that container
3. **Idempotency:** Scan the same bag again → no duplicate, existing quantity returned
4. **Stock adjustment:** Change quantity in dashboard → updates via WebSocket
5. **Search:** Search for a part number that exists in 2+ containers → both highlighted green
