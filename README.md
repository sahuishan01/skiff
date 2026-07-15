# 📡 TransDecoder: Peer-to-Peer File Transfer System

TransDecoder is a peer-to-peer file transfer platform designed to support unlimited file sharing directly between devices. It utilizes **UDP Hole Punching** to establish direct socket-to-socket connections behind NATs/firewalls and uses **QUIC** for multiplexed file streaming with byte-level pause and resume capabilities.

---

## 🛠️ Repository Directory Structure

```text
trans_decoder/
├── trans_decoder_server/       # Rust Axum Signaling Server
│   ├── Cargo.toml
│   ├── migrations/            # DB schemas for PostgreSQL 15+
│   └── src/
│       ├── main.rs            # Entrypoint
│       ├── config.rs          # Port & DB Config
│       ├── db.rs              # PostgreSQL pool & auto-migrations
│       ├── models.rs          # DB models & WsMessage structures
│       ├── signaling.rs       # In-memory routing & pairing code map
│       └── handlers/
│           ├── ws.rs          # WebSocket signaling core
│           └── api.rs         # REST endpoints for transfer health
│
├── trans_decoder_app/          # Kotlin Android Client Application
│   └── (Implement Jetpack Compose UI, Room Local DB, and WebRTC/QUIC socket handler)
```

---

## 🚀 Running the signaling server

### 1. Database Setup
The server automatically applies SQL migrations on startup. Make sure you have a running PostgreSQL database.

If using Podman/Docker, you can spin up the DB container:
```bash
podman run --name trans-decoder-db-pg18 -p 127.0.0.1:5434:5432 \
  -e POSTGRES_DB=trans_decoder \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -d docker.io/library/postgres:18-alpine
```

### 2. Run the Rust signaling server
Set the database URL and port, then run `cargo run`:
```bash
cd trans_decoder_server

# Set environment variables
export DATABASE_URL=postgres://postgres:postgres@127.0.0.1:5434/trans_decoder
export PORT=8080

cargo run
```

---

## 📱 Kotlin Client Integration Flow

The client app should perform these tasks:

1. **Unique Code Registration**:
   * Generate a UUID on first launch and store it locally (e.g. in `SharedPreferences`).
   * Connect to `ws://<server-ip>:8080/ws`.
   * Send a `REGISTER` message with your device UUID.
   * Store the returned 6-character connection code to display to the user.

2. **Connection Pairing**:
   * User B enters User A's 6-character pairing code.
   * User B sends `REQUEST_CONNECTION` with User A's code.
   * Server relays `INCOMING_REQUEST` to User A.
   * If User A accepts, it responds with `ACCEPT_REQUEST`.
   * Server relays `REQUEST_ACCEPTED` to User B.

3. **NAT Hole Punching & Peer Discovery**:
   * Once pairing is accepted, both clients query a STUN server (or the signaling server's UDP mapping port) to obtain their respective public endpoint (`IP:Port`).
   * Exchange public endpoints via WebSocket.
   * Simultaneously send UDP ping packets to each other's public endpoints to punch holes in the NAT firewall.

4. **Reliable QUIC stream**:
   * Establish a secure QUIC connection over the punched UDP socket.
   * Send files in parallel over separate QUIC streams.
   * Store progress locally in SQLite (Room) and report progress back to the server using the `UPDATE_PROGRESS` message to enable exact-byte resume upon reconnection.
