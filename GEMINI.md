# Skiff P2P Project Rules & Best Practices

Enforce these strict guidelines for network connection lifecycles, database operations, and release versioning:

## 1. 📦 Android Release Versioning Rules
* **No Version Increment on Build Failure**: Never increment the application version code or version name (`versionCode` or `versionName` in `app/build.gradle.kts` and workflow yaml tags) if the remote compiler build fails. Keep the version identical and re-trigger compile runs with compilation fixes to avoid version clutter.

## 2. ⚡ WebSocket Client Stability
* **Keepalive Pings**: Always configure a heartbeat ping interval (e.g., `.pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)`) on the OkHttp client builder to send periodic ping frames. This keeps the TCP WebSocket connection open through intermediate reverse proxies and firewalls.
* **Auto-Reconnection**: WebSocket clients must implement an auto-reconnect strategy on connection closed or failed states, waiting with a short delay (e.g., 5 seconds) before clean re-initialization.

## 3. ⏱️ Network and Database Write Throttling
* **Throttled Updates in Stream Loops**: Do not execute SQLite database writes or dispatch WebSocket progress updates on every single binary packet chunk (e.g., 64KB buffers). Throttle updates to at most once every **500ms** (plus a final execution upon completion) to prevent I/O locks and WebSocket buffer floods.

## 4. 🗄️ Room Database Concurrency
* **Write-Ahead Logging (WAL)**: For high-frequency read/write operations or background worker threads, configure the database builder with `.setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)` to allow concurrent reads and writes without thread blocking.
* **Explicit Enum TypeConverters**: Do not rely on Room's default Enum serialization. Register explicit `@TypeConverter` mappers for all custom database enums to ensure clean String persistence.
