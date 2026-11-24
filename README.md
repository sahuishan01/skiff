# ⛵ Skiff

![Android](https://img.shields.io/badge/Android-Kotlin%20%2B%20Compose-3DDC84?logo=android&logoColor=white)
![Rust](https://img.shields.io/badge/server-Rust%20%2B%20Axum-DEA584?logo=rust&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-signaling%20state-4169E1?logo=postgresql&logoColor=white)
![P2P](https://img.shields.io/badge/transfer-direct%20TCP%20%2B%20relay-6E56CF)
![Version](https://img.shields.io/badge/app-0.0.36-2ea44f)

**Peer-to-peer file sharing for Android.** Pair two phones with a 6-digit code and send files straight between them. On the same network the bytes go directly over a TCP socket. When a direct connection isn't possible, Skiff falls back to a streaming relay that passes the bytes through in memory and never stores your files on the server.

---

## ✨ Features

| | Feature |
|---|---|
| 🔢 | **Pair with a code.** Each device gets a 6-digit sharing code. Enter a friend's code, they accept, and you're connected. |
| ⚡ | **Direct transfers.** Files stream over a TCP socket between the two devices, with a hash and a byte offset in the header so an interrupted transfer can pick up where it left off. |
| 🛟 | **Automatic relay fallback.** If the direct connection fails within 3 seconds (for example on mobile data behind CGNAT), the file streams through the server over HTTP instead, with zero disk use on the server. |
| 👥 | **Groups.** Create a persistent group, share a 6-digit group code, and send files to every member. Groups also have a chat. |
| 💬 | **Messages.** Store-and-forward chat between devices. |
| 📂 | **Your folder.** Pick any save location with Android's folder picker, remembered across reboots. No broad storage permission is needed. |
| 📊 | **Live transfers.** A list of in-progress and finished transfers with progress, kept in a local Room database. |
| 🔔 | **Runs in the background.** A foreground service keeps the connection alive when the screen is off. |

---

## 🗺️ How it works

```mermaid
flowchart LR
    A["📱 Sender"] -- "1. WebSocket: pair with code" --> S["🦀 Skiff server<br/>signaling hub"]
    S -- "2. incoming request" --> B["📱 Receiver"]
    A == "3a. direct TCP :8096<br/>same network" ==> B
    A -. "3b. HTTP upload if direct fails" .-> R["🔁 In-memory relay<br/>no disk"]
    R -. "download stream" .-> B
```

```mermaid
sequenceDiagram
    participant A as 📱 Sender
    participant S as 🦀 Server
    participant B as 📱 Receiver
    A->>S: Register, then PairRequest(code)
    S->>B: IncomingRequest
    B->>S: Accept
    S->>A: RequestAccepted (with receiver address)
    A->>S: IncomingTransfer (file list)
    S->>B: IncomingTransfer
    alt same network
        A->>B: Stream file over TCP
    else direct connection fails
        A->>S: RELAY_FALLBACK, upload stream
        S->>B: download stream
    end
```

The server is a stateless signaling hub plus relay. It routes pairing and group messages over WebSocket (`/ws`, with 15 second keepalive pings), and relays fallback transfers through bounded in-memory channels (`/api/relay/upload/:id` and `/api/relay/download/:id`). PostgreSQL stores transfer sessions, devices, groups and chat messages.

---

## 🚀 Run the server

You need **Rust 1.75 or newer** and a **PostgreSQL** database. The server applies its migrations on startup.

```bash
# a throwaway database with Podman or Docker
podman run --name skiff-db -p 127.0.0.1:5434:5432 \
  -e POSTGRES_DB=trans_decoder -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
  -d docker.io/library/postgres:18-alpine

cd trans_decoder_server
export DATABASE_URL=postgres://postgres:postgres@127.0.0.1:5434/trans_decoder
export PORT=8095
cargo run --release
```

| Variable | Purpose |
|---|---|
| `DATABASE_URL` | PostgreSQL connection string |
| `PORT` | Port to listen on. Set it explicitly. |
| `RUST_LOG` | Log level (`debug`, `info`, `warn`, `error`) |

Check it with `curl http://127.0.0.1:8095/health`. For production, run it as a service behind a TLS reverse proxy that passes WebSocket and streaming requests through. [trans_decoder_server/handoff.md](trans_decoder_server/handoff.md) has the full setup, a systemd unit and a Caddy example.

---

## 📱 Build the Android app

Open `trans_decoder_app` in Android Studio, or build from the command line with JDK 17:

```bash
cd trans_decoder_app
gradle wrapper && ./gradlew assembleRelease
```

The app targets Android 7.0 or newer (API 24). Point it at your own server by changing the two URL constants in `app/src/main/java/com/transdecoder/Config.kt` (the WebSocket URL and the HTTP base URL). Without signing settings, `assembleRelease` produces an unsigned APK.

### 🔏 Release signing

Release signing keys are never stored in the repo. Pushes to `main` run the [build workflow](.github/workflows/build.yml), which signs the APK from repository secrets and publishes a GitHub Release. If the keystore secret isn't set, it builds an unsigned APK and skips the release.

Create a keystore once and keep it somewhere safe, because every update must be signed with the same key:

```bash
keytool -genkeypair -v -keystore skiff-release.keystore -alias skiff \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 skiff-release.keystore | gh secret set SKIFF_KEYSTORE_BASE64
gh secret set SKIFF_KEYSTORE_PASSWORD
gh secret set SKIFF_KEY_ALIAS          # skiff
gh secret set SKIFF_KEY_PASSWORD
```

For a local signed build, set the same names as environment variables (`SKIFF_KEYSTORE_FILE` is the path to the file) before running Gradle.

---

## 🗂️ Project layout

```
trans_decoder_server/        Rust server
  src/handlers/              ws.rs (signaling) and api.rs (health, relay, transfer status)
  src/signaling.rs           in-memory connection and relay state
  migrations/                devices, transfers, chat messages, groups
trans_decoder_app/           Kotlin Android app
  .../SkiffBackgroundService.kt   WebSocket, TCP and relay transfer engine
  .../MainActivity.kt             Compose UI (pairing, groups, transfers, save location)
  .../data/local/                 Room database
verify_signaling.py, test_cross_network.py   diagnostic scripts
```

[HANDOFF.md](HANDOFF.md) has the detailed architecture and transfer lifecycle.
