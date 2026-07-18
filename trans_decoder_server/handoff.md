# ⛵ Skiff P2P Backend Server - Complete Handoff & Setup Guide

This document provides a step-by-step guide to configuring, compiling, deploying, and maintaining the **Skiff P2P Signaling & HTTP Streaming Relay Server**.

---

## 🏗️ 1. Server Architecture & Features

The Skiff backend is built with **Rust (Axum + Tokio)** for high-throughput, low-latency asynchronous I/O and zero-allocation streaming.

### Core Capabilities:
1. **WebSocket Signaling Hub (`/ws`)**:
   - Manages 6-digit device pairing codes (`PairRequest`, `PairResponse`).
   - Relays network candidates (`LOCAL_IP:`, `RELAY_FALLBACK:`) between peers.
   - Enforces 15-second OkHttp keepalive ping frames to maintain TCP sockets through proxies.

2. **In-Memory Streaming Relay (`/api/relay/upload/:file_id` & `/api/relay/download/:file_id`)**:
   - Provides HTTP fallback when direct TCP socket connection fails (e.g., cross-country or CGNAT mobile data blocks).
   - **Zero-Disk Overhead**: Uses Tokio bounded channels (`tokio::sync::mpsc`) to stream raw byte chunks directly from sender upload to receiver download in-memory without saving files to server storage.

3. **PostgreSQL Session Persistence**:
   - Uses `sqlx` with Write-Ahead Logging for tracking transfer session history and progress metadata.

---

## 📋 2. Environment Prerequisites

Ensure the following tools are installed on the host system:
* **Rust Toolchain**: 1.75+ (`rustc`, `cargo`)
* **PostgreSQL**: 14+ (or Docker container running PostgreSQL)
* **Reverse Proxy**: Caddy (recommended) or Nginx
* **Systemd**: Linux service manager

---

## 🚀 3. Step-by-Step Setup Guide

### Step 1: Clone Repository & Database Setup
Initialize PostgreSQL database and user:
```bash
# Create database user and database in PostgreSQL
sudo -u postgres psql -c "CREATE USER postgres WITH PASSWORD 'postgres';"
sudo -u postgres psql -c "CREATE DATABASE trans_decoder OWNER postgres;"
```

Run migrations (located in `trans_decoder_server/migrations`):
```sql
CREATE TYPE session_status AS ENUM ('PENDING', 'CONNECTED', 'TRANSFERRING', 'COMPLETED', 'FAILED', 'CANCELLED');
CREATE TYPE transfer_status AS ENUM ('PENDING', 'TRANSFERRING', 'COMPLETED', 'FAILED', 'CANCELLED');

CREATE TABLE transfer_sessions (
    session_id UUID PRIMARY KEY,
    sender_device_id TEXT NOT NULL,
    receiver_device_id TEXT NOT NULL,
    status session_status NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE transfer_files (
    file_id TEXT PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES transfer_sessions(session_id),
    file_name TEXT NOT NULL,
    file_path TEXT NOT NULL,
    file_size BIGINT NOT NULL,
    file_hash TEXT NOT NULL,
    bytes_transferred BIGINT NOT NULL DEFAULT 0,
    status transfer_status NOT NULL DEFAULT 'PENDING',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### Step 2: Build the Release Binary
Change to the server directory and build in `--release` mode:
```bash
cd /root/services/trans_decoder/trans_decoder_server
cargo build --release
```
The optimized executable binary will be generated at:
`/root/services/trans_decoder/trans_decoder_server/target/release/skiff_server`

### Step 3: Configure Systemd Service
Create the service unit file at `/etc/systemd/system/skiff.service`:
```ini
[Unit]
Description=Skiff P2P Signaling & Relay Server
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/root/services/trans_decoder/trans_decoder_server
Environment="DATABASE_URL=postgres://postgres:postgres@127.0.0.1:5434/trans_decoder"
Environment="PORT=8095"
Environment="RUST_LOG=info"
ExecStart=/root/services/trans_decoder/trans_decoder_server/target/release/skiff_server
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Enable and start the service:
```bash
sudo systemctl daemon-reload
sudo systemctl enable skiff
sudo systemctl start skiff
sudo systemctl status skiff
```

### Step 4: Reverse Proxy Configuration (Caddy)
Add the domain entry to your `Caddyfile`:
```caddy
skiff.algosculptor.com {
	tls /certs/origin.pem /certs/origin.key
	encode zstd gzip
	reverse_proxy 127.0.0.1:8095
}
```
Reload Caddy:
```bash
caddy reload --config /etc/caddy/Caddyfile
```

---

## 🛠️ 4. Verification & Diagnostic Commands

### 1. Test Server Health Check Endpoint
```bash
curl -i https://skiff.algosculptor.com/health
# Expected Output: HTTP/2 200 OK -> "OK"
```

### 2. Test Streaming HTTP Relay Upload Endpoint
```bash
curl -v -X POST -H "Content-Type: application/octet-stream" \
  --data-binary "hello world test payload" \
  https://skiff.algosculptor.com/api/relay/upload/test-file-id
# Expected Output: HTTP/2 200 OK
```

### 3. Check Live Server Logs
```bash
journalctl -u skiff -f --no-pager
```

---

## 🔒 5. Essential Rules & Best Practices
* **Compilation Rule**: Always run `cargo build --release` when updating server source files in `src/`. Running `cargo check` only verifies syntax without updating the production binary used by `skiff.service`.
* **Keepalive Ping**: The OkHttp client on Android sends keepalives every 15 seconds to keep WebSocket channels open across proxies. Do not reduce heartbeat timeout below 15s.
