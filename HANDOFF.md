# ⛵ Skiff P2P File Sharing — Complete Project Handoff

**Skiff** is a cross-device, peer-to-peer file sharing application for Android. It transfers files over the local network using a direct TCP socket connection, with an automatic fallback to an HTTP streaming relay server hosted in the cloud when devices are on different networks (e.g., mobile data vs. Wi-Fi, or cross-country).

---

## 🗂️ Repository Structure

```
trans_decoder/
├── .github/
│   └── workflows/
│       └── build.yml                 # GitHub Actions: auto-builds APK on push
├── trans_decoder_app/                # Android Kotlin Application (Jetpack Compose)
│   └── app/
│       └── src/main/java/com/transdecoder/
│           ├── Config.kt             # Server URL constants
│           ├── MainActivity.kt       # Main UI — pairing, transfers, save location
│           ├── SkiffBackgroundService.kt  # Core logic: WebSocket, TCP, Relay
│           └── data/
│               ├── local/            # Room SQLite database
│               │   ├── AppDatabase.kt
│               │   ├── Converters.kt
│               │   ├── TransferDao.kt
│               │   └── TransferEntity.kt
│               └── network/
│                   └── WebSocketClient.kt  # OkHttp WebSocket wrapper
├── trans_decoder_server/             # Rust (Axum + Tokio) signaling & relay server
│   ├── src/
│   │   ├── main.rs                   # Entry point, router, Axum app setup
│   │   ├── config.rs                 # PORT and DATABASE_URL env config
│   │   ├── db.rs                     # SQLx PostgreSQL pool initializer
│   │   ├── models.rs                 # WsMessage enum, DB model structs
│   │   ├── signaling.rs              # In-memory state: connections, relay sessions
│   │   └── handlers/
│   │       ├── api.rs                # HTTP REST + relay upload/download handlers
│   │       └── ws.rs                 # WebSocket upgrade + message dispatch
│   ├── migrations/                   # SQLx SQL migration files
│   ├── handoff.md                    # Backend-specific setup guide
│   └── Cargo.toml
├── GEMINI.md                         # AI assistant workspace rules
├── verify_signaling.py               # Python WebSocket diagnostic tool
└── HANDOFF.md                        # This file
```

---

## 📱 Android Application (`trans_decoder_app`)

### Overview
A Jetpack Compose app targeting Android API 24+. The UI runs in `MainActivity`, while all networking runs inside `SkiffBackgroundService` — a persistent foreground service that survives screen lock and app backgrounding.

### Key Files & Responsibilities

#### `Config.kt`
Single object with server URL constants:
```kotlin
object Config {
    const val SIGNALING_SERVER_URL = "wss://skiff.algosculptor.com/ws"
    const val API_SERVER_URL = "https://skiff.algosculptor.com"
}
```
> **To change server:** Update both values here. No other file needs to change.

#### `MainActivity.kt`
The entry point and entire UI. Built with Jetpack Compose and a root `LazyColumn`:

| UI Card | Purpose |
|---|---|
| Sharing Code Card | Displays the local device's 6-digit pairing code |
| Connection Status Card | Shows WebSocket status with a reconnect button |
| Save Location Card | Lets user pick a custom save folder via Android SAF picker |
| Connect to Peer Card | Input field to enter a peer's 6-digit code and pair |
| Incoming Pair Request | Tappable accept/reject notification card |
| Send Files Card | File picker button; sends selected files to paired peer |
| Transfer List | Live-updating list of all in-progress and completed transfers |

**Folder Picker Flow:**
1. User taps "Change Save Location" → launches `ActivityResultContracts.OpenDocumentTree()`
2. Android system folder picker opens; user selects any directory
3. App calls `takePersistableUriPermission()` to obtain permanent read/write access across reboots
4. URI string is stored in `SharedPreferences("skiff_prefs", "custom_save_path_uri")`
5. `SkiffBackgroundService` reads this preference when routing incoming file writes

#### `SkiffBackgroundService.kt`
The core engine. Exposes shared state via `companion object` `MutableStateFlow` properties consumed by `MainActivity`:

| State Flow | Purpose |
|---|---|
| `deviceCode` | Local device 6-digit code shown in UI |
| `connectionStatus` | Current connectivity string |
| `activeIncomingRequest` | Holds sender's deviceId + code for incoming pair prompt |
| `activePeerDeviceId` | The currently paired device ID |

**Transfer Lifecycle (Sender Side):**

1. User selects files → `sendFiles()` registers each file in Room DB with `PENDING` status, then sends `IncomingTransfer` WebSocket message to the receiver, then calls `sendFileTcp()` per file
2. `sendFileTcp()` attempts a **3-second TCP connect** to `peerIpAddress:8096`
3. **If connect succeeds:** Streams bytes over TCP with `FILE_ID|HASH|OFFSET\n` header prefix. DB + WS progress updates throttled to ≥500ms
4. **If connect fails:** Sends `IceCandidate("RELAY_FALLBACK:fileId")` to receiver via WebSocket, then calls `uploadFileRelay()`
5. `uploadFileRelay()` POSTs the file as a streaming `application/octet-stream` body to `https://skiff.algosculptor.com/api/relay/upload/{fileId}`

**Transfer Lifecycle (Receiver Side):**

1. Server relays `IncomingTransfer` WebSocket message → service inserts DB records per file
2. File path is resolved from:
   - **Custom SAF directory URI** (from SharedPreferences) if set and writable → uses `DocumentFile.createFile()` → returns a `content://` URI
   - **Default:** `getExternalFilesDir(DIRECTORY_DOWNLOADS)` → returns a file path string
3. TCP server on port `8096` accepts sender's socket and streams bytes to the resolved output stream via `ContentResolver.openOutputStream()` or `FileOutputStream`
4. If a `RELAY_FALLBACK:fileId` WebSocket message arrives instead, calls `downloadFileRelay()` which GETs the streaming body from the relay server and writes it the same way

**Threading Rules:**
- All network and DB work runs on `Dispatchers.IO` inside `CoroutineScope`
- Progress updates are throttled to **≥500ms intervals** to avoid SQLite I/O locks and WebSocket floods

#### `WebSocketClient.kt`
Wraps OkHttp WebSocket with:
- **15-second keepalive ping interval** (`.pingInterval(15, TimeUnit.SECONDS)`) to prevent firewalls and Caddy from timing out the TCP connection
- **Auto-reconnect on close/failure** with a 5-second delay before clean re-initialization
- JSON serialization via `kotlinx.serialization`

#### Room Database (`data/local/`)

| File | Purpose |
|---|---|
| `AppDatabase.kt` | Room singleton; configured with `WRITE_AHEAD_LOGGING` journal mode |
| `TransferEntity.kt` | Table: `fileId`, `filePath`, `fileSize`, `bytesTransferred`, `status`, `direction`, `peerDeviceId`, `sessionId`, `fileName`, `fileHash` |
| `TransferDao.kt` | `insertTransfer`, `updateProgress`, `getTransferByIdAndDirection`, `getAllTransfersFlow`, `getAllTransfersList` |
| `Converters.kt` | Explicit `@TypeConverter` for `TransferStatus` and `TransferDirection` enums to/from String |

### Android Manifest Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```
> No `READ/WRITE_EXTERNAL_STORAGE` needed — all file access uses the Storage Access Framework (SAF) via `ContentResolver`.

### Build & Release

**Version files to update together:**

1. `trans_decoder_app/app/build.gradle.kts` — `versionCode` and `versionName`
2. `.github/workflows/build.yml` — `tag_name: vX.X.X` and `name: Release vX.X.X`

Then `git push` → GitHub Actions builds a signed APK and publishes a GitHub Release automatically.

> ⚠️ **Rule**: Never bump the version if the CI build fails. Fix compile errors first, then re-trigger with the same version number.

---

## 🖥️ Rust Backend (`trans_decoder_server`)

### Overview
A stateless, async Rust server built on **Axum + Tokio**. It serves two roles:
1. **WebSocket Signaling Hub** — routes device pairing and ICE candidate messages between two Android clients
2. **HTTP Streaming Relay** — passes file bytes in-memory between two HTTP connections without writing to disk

### API Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/health` | Health check — returns `200 OK` |
| `GET` | `/ws` | WebSocket upgrade endpoint for devices |
| `GET` | `/api/transfers/:session_id` | Fetch session + file status from PostgreSQL |
| `POST` | `/api/relay/upload/:file_id` | Sender streams file bytes here |
| `GET` | `/api/relay/download/:file_id` | Receiver pulls the stream here |

### WebSocket Message Types

| Message | Direction | Purpose |
|---|---|---|
| `Register` | Client → Server | Register `device_id` and `device_code` |
| `PairRequest` | Client → Server | Request pairing with peer by their 6-digit code |
| `IncomingRequest` | Server → Client | Notify receiver of incoming pair request |
| `RequestAccepted` | Server → Client | Notify sender pairing was accepted; includes receiver endpoint IP |
| `RequestRejected` | Server → Client | Notify sender pairing was rejected |
| `IncomingTransfer` | Server → Client | Relay file metadata array to receiver |
| `IceCandidate` | Client ↔ Client | Pass network candidates: `LOCAL_IP:x.x.x.x` or `RELAY_FALLBACK:fileId` |
| `UpdateProgress` | Client → Client | Relay transfer `bytes_transferred` + `status` to receiver UI |

### Relay Architecture (Zero-Disk Streaming)

```
Sender → POST /api/relay/upload/{id}
  relay_upload() reads body chunks via body.into_data_stream()
    → sends bytes into mpsc::Sender<Bytes>  (stored in relay_sessions HashMap)

Receiver → GET /api/relay/download/{id}
  relay_download() takes the mpsc::Receiver<Bytes>
    → wraps it in RelayStream (implements futures::Stream)
      → returned as Axum streaming response body
```

No disk I/O on the server. The receiver starts receiving data as soon as the first chunk arrives from the sender.

### Environment Variables

| Variable | Required | Description |
|---|---|---|
| `DATABASE_URL` | ✅ | PostgreSQL connection string |
| `PORT` | default `8095` | Port the server listens on |
| `RUST_LOG` | default `info` | Log level (`debug`, `info`, `warn`, `error`) |

### Deployment

The server runs as a `systemd` service. See [`trans_decoder_server/handoff.md`](trans_decoder_server/handoff.md) for the full DB schema, systemd unit file, and Caddy config.

**Essential commands:**

```bash
# After ANY source code change — rebuild the release binary
cd /root/services/trans_decoder/trans_decoder_server
cargo build --release

# Deploy the new binary
systemctl restart skiff

# View live logs
journalctl -u skiff -f --no-pager

# Test relay endpoint locally
curl -v -X POST -H "Content-Type: application/octet-stream" \
  --data-binary "test" http://127.0.0.1:8095/api/relay/upload/test-id
```

> ⚠️ **Critical:** `cargo check` only validates syntax. The `skiff.service` systemd unit runs the binary from `target/release/`. Always run `cargo build --release` to update it.

### Caddy Reverse Proxy

The server is exposed via a Caddy Docker container at `/root/services/Caddyfile`:

```caddy
skiff.algosculptor.com {
    tls /certs/origin.pem /certs/origin.key
    encode zstd gzip
    reverse_proxy 10.89.1.1:8095
}
```

Cloudflare sits in front of Caddy → all Android client requests flow: `Client → Cloudflare → Caddy → skiff_server:8095`.

The `10.89.1.1` IP is the Podman bridge gateway that routes into the host network where `skiff_server` is listening.

---

## 🔄 End-to-End Transfer Flow

### Same-Network Transfer (TCP Direct)

```
Sender App                   Skiff Server              Receiver App
    │                             │                          │
    │── Register(deviceId) ──────▶│                          │
    │                             │◀── Register(deviceId) ───│
    │── PairRequest(peerCode) ───▶│                          │
    │                             │── IncomingRequest ───────▶│
    │                             │◀── PairResponse(accept) ──│
    │◀── RequestAccepted ─────────│                          │
    │── IceCandidate(LOCAL_IP:x.x.x.x) ────────────────────▶│
    │── IncomingTransfer(files[]) ──────────────────────────▶│
    │                             │                          │
    │════════ Direct TCP Socket :8096 ═════════════════════▶ │
    │              streams file bytes                         │
```

### Cross-Network Transfer (HTTP Relay)

```
Sender App                   Skiff Server              Receiver App
    │                             │                          │
    │── [TCP connect to :8096 fails with 3s timeout]         │
    │── IceCandidate(RELAY_FALLBACK:fileId) ────────────────▶│
    │                             │                          │
    │── POST /api/relay/upload/{fileId} ──▶ mpsc::channel ──▶ GET /api/relay/download/{fileId} ──▶│
    │       (streams bytes via OkHttp)    (zero-disk relay)   (writes to SAF or Downloads dir)
```

---

## ⚙️ Project Rules

Encoded in [`GEMINI.md`](GEMINI.md) and must always be followed:

| Rule | Detail |
|---|---|
| **No version bump on build failure** | Keep `versionCode`/`versionName` identical if CI build fails. Fix and retry with same version. |
| **WebSocket keepalive pings** | Always `.pingInterval(15, TimeUnit.SECONDS)` on OkHttp client builder. |
| **Progress throttle ≥500ms** | Never write DB or send WebSocket progress on every packet. Throttle to once per 500ms. |
| **Room WAL mode** | `.setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)` for concurrent reads during writes. |
| **Explicit Enum TypeConverters** | Always use `@TypeConverter` String mappers for `TransferStatus` and `TransferDirection`. |
| **`cargo build --release` required** | Must rebuild release binary and restart service after every Rust source change. |

---

## 🔧 Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Relay upload returns `502 Bad Gateway` | Server binary stale; new routes not compiled into release | `cargo build --release && systemctl restart skiff` |
| `File entity not found in database for ID: ...` | `IncomingTransfer` WS message not yet processed when TCP data arrives | The DB retry loop polls for 5s (50×100ms). Usually a WS latency issue; check server logs |
| WebSocket disconnects on idle | Proxy/firewall timing out inactive TCP | Verify OkHttp `pingInterval` is set to 15s |
| Transfer fails after network switch (Wi-Fi ↔ Mobile) | TCP socket bound to old interface IP | Expected — client auto-reconnects WS and re-exchanges `LOCAL_IP:` candidate |
| `RELAY_FALLBACK` even on same LAN | 3s TCP connect timeout too short | Increase in `sendFileTcp()`: `socket.connect(InetSocketAddress(ip, 8096), 5000)` |
| OkHttp `MediaType.parse()` compile error | Using deprecated Java API instead of Kotlin extension | Use `"application/octet-stream".toMediaTypeOrNull()` with import `okhttp3.MediaType.Companion.toMediaTypeOrNull` |
| Room `String? but String expected` compile error | `var filePath: String?` assigned where non-null required | Use `val savePath: String = destinationPath ?: fallback` before passing to entity |

---

## 🚀 Release History

| Version | Download | Key Features |
|---|---|---|
| **v0.0.22** | [APK](https://github.com/sahuishan01/skiff/releases/download/v0.0.22/app-release.apk) | Custom SAF save folder picker, persistent directory permissions |
| **v0.0.21** | [APK](https://github.com/sahuishan01/skiff/releases/download/v0.0.21/app-release.apk) | Cross-network HTTP streaming relay, 3s TCP timeout, relay server Axum endpoints |
| **v0.0.20** | [APK](https://github.com/sahuishan01/skiff/releases/download/v0.0.20/app-release.apk) | Room WAL mode, explicit Enum TypeConverters, DB diagnostic logging |
| **v0.0.19** | [APK](https://github.com/sahuishan01/skiff/releases/download/v0.0.19/app-release.apk) | Room WAL journal mode |
| **v0.0.18** | [APK](https://github.com/sahuishan01/skiff/releases/download/v0.0.18/app-release.apk) | OkHttp keepalive pings, 500ms progress throttle |
