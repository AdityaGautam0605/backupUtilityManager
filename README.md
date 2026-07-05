# Database Backup Utility CLI

A Java CLI tool for backing up PostgreSQL, MySQL, and MongoDB databases — with an AI layer for root cause analysis, compression strategy advice, and natural language input parsing.

---

## Current Architecture

```
com.backuputil/
├── ai/
│   ├── RootCauseAnalyser.java      — AI-powered failure/success analysis
│   ├── CompressionAdvisor.java     — recommends + confirms compression strategy
│   ├── NaturalLanguageParser.java  — parses plain English into backup config
│   ├── RestoreAdvisor.java         — AI-guided interactive restore (point-in-time + decompression detection)
│   └── BackupReportGenerator.java  — human-readable post-backup report with trends
├── cli/
│   └── BackupCommand.java          — picocli entrypoint, wires everything together
├── config/
│   ├── AppConfig.java              — API key + mock mode singleton
│   └── DbConfig.java               — immutable DB connection config
├── model/
│   ├── BackupResult.java           — structured result of a backup run
│   ├── RestoreResult.java          — structured result of a restore run
│   ├── CompressionStrategy.java    — enum: GZIP, BZIP2, LZ4, ZSTD (+ extension auto-detect)
│   └── ParsedIntent.java           — output of natural language parsing
├── util/
│   └── CompressionStreams.java     — decompression helpers (magic-byte aware) for restore
└── service/
    ├── DatabaseService.java        — interface
    └── impl/
        ├── PostgresService.java
        ├── MysqlService.java
        └── MongoService.java

org.example/  (legacy package — Main.java still lives here, should be moved to com.backuputil)
└── Main.java
```

---

## What's Done

### Core Fixes
- Fixed stderr deadlock — backup/restore no longer hangs on large databases (background thread drains stderr concurrently with stdout)
- Fixed MySQL password exposure — password now passed via `MYSQL_PWD` env var instead of CLI arg (no longer visible in `ps aux`)
- Fixed `--mock` flag — no longer required on every run, defaults to `false`, all three services gate their test bypasses behind `config.isMock()` consistently
- Fixed package structure — `DatabaseService` interface moved from `service.impl` to `service`
- Introduced `BackupResult` — structured return type (status, db name/type, file size, duration, exit code, error message, timestamp) instead of swallowed print statements

### Phase 1 — Core AI Layer
- `AppConfig` singleton reads `ANTHROPIC_API_KEY` and `MOCK_AI` from environment variables
- `RootCauseAnalyser` analyses both successful and failed backups
    - Mock mode: rule-based canned analysis (no API calls, free to test)
    - Real mode: calls Claude API (`claude-3-5-haiku`) via raw `HttpClient`, no SDK dependency
- Wired into `BackupCommand` — analysis prints after every backup run

### Phase 2 — Compression Intelligence
- `CompressionStrategy` enum (GZIP, BZIP2, LZ4, ZSTD) with availability detection via `which`/`where` (3s timeout, GZIP always available since it's JDK-native)
- `CompressionAdvisor` recommends a strategy based on estimated DB size + backup frequency, shows reasoning, lets user accept or override, falls back to GZIP if the chosen algorithm isn't installed
- All three services updated to use `strategy.getExtension()` for filenames and `buildCompressionStream()` for actual compression (reflection-based — falls back to GZIP gracefully if BZIP2/LZ4/ZSTD libraries aren't on the classpath yet)
- `--backup-frequency` CLI flag added

### Phase 3 — Natural Language CLI
- `--nl "<sentence>"` flag lets users describe what they want in plain English instead of remembering flags
- `NaturalLanguageParser`:
    - Mock mode: regex-based extraction (dbType, host, port, user, dbName, mock flag)
    - Real mode: Claude API call with strict JSON-only system prompt
    - **Passwords are never extracted from natural language, by design** — always prompted separately
- Missing required fields (`-t`, `-u`, `-d`) are now optional at the CLI level and get interactively prompted if still missing after NL parsing
- Tested end-to-end with real Postgres connection attempts — works correctly

### Phase 4 — Restore + Report
- `restore()` is now implemented in all three services (was a stub). Each decompresses the archive and streams it into the native client:
    - Postgres → `psql` stdin, password via `PGPASSWORD`
    - MySQL → `mysql` client stdin, password via `MYSQL_PWD`
    - Mongo → `mongorestore --archive` stdin
    - Same background-virtual-thread draining used on backup, applied to both stdout and stderr to avoid the OS-buffer deadlock
- `RestoreResult` — structured restore outcome (status, db, source, bytes restored, duration, exit code, error, timestamp)
- `RestoreAdvisor` — AI-guided interactive restore:
    - **Point-in-time selection**: lists existing backups for the database (timestamp + size parsed from filenames), user picks the snapshot
    - **Decompression auto-detection** from the file extension, with a magic-byte safety net (a file labelled `.zst` that actually holds GZIP bytes — the current backup fallback reality — is still read correctly)
    - **Destructive-operation confirmation gate** before touching the live database
    - **AI guidance**: rule-based safety briefing in mock mode, Claude in real mode (graceful fallback)
- `BackupReportGenerator` — human-readable post-backup report:
    - Summary (status, size, duration, throughput, exit code) plus a **Trends** section derived by scanning prior backups on disk (count, total size, delta vs previous run) and **recommendations** (growth spikes, 0-byte archives, pruning suggestions)
    - Printed after every backup; `--report` also writes it to `<db>_<timestamp>_report.txt`
- New CLI flags: `--restore` (interactive picker), `--restore-file <path>` (direct), `--report`
- `util/CompressionStreams` — centralised, magic-byte-aware decompression for the restore path
- **Correctness fixes made alongside this phase:** MySQL backup now actually passes the password (`MYSQL_PWD` was never set — real MySQL backups were broken); MySQL error-path and Mongo result objects now report the correct `dbType` (were hard-coded to the wrong engine)

### Follow-up fixes (compression heuristic + JSON parsing)
- **Compression advisor now uses the real database size.** `estimateDbSize()` was hard-coded to 100 MB, and with the old `> 500` / `< 100` thresholds the recommendation *always* came out ZSTD. Each service now implements `estimateSizeBytes()` (`pg_database_size` / `information_schema` sum / Mongo `dbStats.dataSize`), the advisor feeds that real number in, and the thresholds use inclusive boundaries so every size lands in exactly one tier (mock mode / query failure → balanced ZSTD default).
  - The size is captured **during** `testConnection` on the connection it already opens (cached per run), so the advisor reuses it instead of opening a second connection. `estimateSizeBytes()` keeps a standalone fallback for callers that skip the connection test.
- **Robust Claude response parsing.** The old `indexOf("text") … lastIndexOf("\"")` approach captured trailing JSON (`stop_reason`, `usage`) on real API responses. Replaced with a single escape-aware extractor, `util/ClaudeResponse.extractText()`, now used by `RootCauseAnalyser`, `NaturalLanguageParser`, and `RestoreAdvisor`.

> Note: implemented but not yet compiled/run on this machine (no JDK/Maven on PATH) — build + smoke-test in IntelliJ before relying on it.

---

## What's Pending

### Phase 5 — Cloud Upload (not started, deferred — cost)
- `CloudUploader` interface only for now
- Target implementation: AWS S3 (most generous free tier — 5GB/12 months)
- Planned structure:
  ```
  service/cloud/
  ├── CloudUploader.java       (interface)
  └── impl/
      └── S3Uploader.java
  ```
- AI-assisted storage tier decisions (e.g. backups older than 30 days → cold storage) — design only, not implemented

### Encryption + Security Layer (designed, not implemented — deferred)
**Reason for deferral:** waiting until comfortable with FastAPI, encryption fundamentals, and authentication concepts before implementing, so the code is fully understood rather than copy-pasted.

Design already discussed and ready to implement when ready:
- AES-256-GCM encryption, applied to every backup automatically (no opt-out)
- Key derivation via PBKDF2WithHmacSHA256 (310,000 iterations, OWASP minimum) — raw passphrase never used directly as key
- Random salt + IV per file, stored in file header (`MAGIC_HEADER + SALT + IV + ciphertext`)
- Key handling: `--key-file` flag OR interactive passphrase prompt
- New files planned: `security/EncryptionService.java`, `security/EncryptionResult.java`
- Zero new dependencies needed — uses JDK's built-in `javax.crypto`

### Correctness pass (2026-07-05)
Made the code match what this README claims. All pending a first clean build in IntelliJ:
- **AI now actually enables.** `AppConfig` read the env var `KEY` while every message said `ANTHROPIC_API_KEY`; it now reads `ANTHROPIC_API_KEY`.
- **Compression is real, not cosmetic.** Added the correct dependencies (`org.apache.commons:commons-compress`, `org.lz4:lz4-java:1.8.0`, `com.github.luben:zstd-jni`) so BZIP2/LZ4/ZSTD load instead of silently falling back to GZIP. `CompressionStrategy.isAvailable()` now probes the classpath for the backing library (what's actually used) rather than running `which`/`where` on a CLI binary that is never invoked.
- **Per-engine default ports.** `--port` now defaults to 5432/3306/27017 based on the resolved DB type instead of a hardcoded 5432 for everything.
- **HTTP timeouts** added to `RootCauseAnalyser` and `NaturalLanguageParser` (they could previously hang the CLI forever).
- **Robust JSON escaping.** All three AI callers now share `util/JsonStrings.escape()`, which escapes every control character (not just `\n`/`\"`), so odd bytes in tool stderr can't produce a malformed request body.
- **MongoDB credentials URL-encoded** in the connection string so a password containing `@ : / ? #` no longer corrupts the URI.
- **Backups no longer committed to git** — `backups/` and `*_report.txt` are gitignored.
- **Unit tests** added for the pure functions (`ClaudeResponse`, `JsonStrings`, `CompressionStrategy`).

### Still Outstanding
- `Main.java` still sits in `org.example` package — should be moved to `com.backuputil` for consistency (left for an IDE refactor so the run configuration updates with it).
- Password masking in terminal input — picocli's `interactive=true` echoes the password in plaintext in some terminal contexts (e.g. IntelliJ Run window). A more robust fix using `System.console().readPassword()` was designed but deliberately skipped as a low-priority cosmetic issue.
- MongoDB still passes `--password` as a process argument (visible in `ps aux`) on both backup and restore — unlike Postgres/MySQL which use env vars. mongodump/mongorestore have no password env var, so this needs a different approach (e.g. `--config` file). The connection string used for the JDBC-style handshake is now URL-encoded, but the argv exposure to the native tools remains.
- Postgres restore replays a plain SQL script without `--clean`/`--if-exists`, so it targets an empty database cleanly but errors on pre-existing objects.

---

## Environment Variables

| Variable | Purpose |
|---|---|
| `ANTHROPIC_API_KEY` | Claude API key for real AI analysis/parsing (not yet obtained) |
| `MOCK_AI` | Set to `true` to use free rule-based mock AI instead of real API calls |

---

## Suggested Next Steps (in priority order)

1. Learn FastAPI, encryption fundamentals, and authentication basics (in progress)
2. Implement the Encryption + Security layer using the design above
3. Move `Main.java` to `com.backuputil` package
4. Fix compression library Maven coordinates (BZIP2/LZ4/ZSTD)
5. Add unit tests
6. Phase 5 — Cloud Upload (S3) once budget allows
7. Write a proper architecture diagram for the README (for resume/portfolio presentation)

---

## Development Log

### 2026-06-27 — Phase 4 + flaw fixes

**Phase 4 (Restore + Report) implemented**
- `restore()` is now real in all three services — decompress the archive and stream it into `psql` / `mysql` / `mongorestore --archive` via stdin, with the same dual virtual-thread draining the backup path uses. The interface now returns a structured `RestoreResult`.
- `RestoreAdvisor` — AI-guided interactive restore: point-in-time picker (snapshots parsed from filenames), extension-based decompression auto-detect with a magic-byte safety net, destructive-operation confirmation gate, and AI guidance (rule-based in mock mode, Claude in real mode).
- `BackupReportGenerator` — human-readable report with a Trends section (count, total size, delta vs previous run) and recommendations, derived by scanning prior backups on disk.
- `util/CompressionStreams` — centralised, magic-byte-aware decompression for the restore path.
- New CLI flags: `--restore`, `--restore-file <path>`, `--report`.

**Correctness fixes**
- MySQL backup never set `MYSQL_PWD` → real MySQL backups were broken. Fixed.
- Mongo reported `dbType` as `"mysql"`; MySQL's error path reported `"postgres"`. Both fixed.
- `mkdirs()` return value is now checked before proceeding.

**Compression heuristic fixed**
- `estimateDbSize()` was hard-coded to 100 MB and the `> 500` / `< 100` thresholds left a gap, so the advisor *always* returned ZSTD. Each service now implements `estimateSizeBytes()` (`pg_database_size` / `information_schema` sum / Mongo `dbStats.dataSize`), and the tiers use inclusive boundaries so every size lands in exactly one branch (mock / query failure → balanced ZSTD default).
- The size is captured **during** `testConnection` on the connection it already opens (cached per run), so the advisor reuses it instead of opening a second connection — one DB round-trip instead of two.

**Robust Claude response parsing**
- The old `indexOf("text") … lastIndexOf("\"")` approach captured trailing JSON (`stop_reason`, `usage`) on real API responses. Replaced with a single escape-aware extractor, `util/ClaudeResponse.extractText()`, now shared by `RootCauseAnalyser`, `NaturalLanguageParser`, and `RestoreAdvisor`.

**Changeset:** 5 new files (`RestoreResult`, `RestoreAdvisor`, `BackupReportGenerator`, `ClaudeResponse`, `CompressionStreams`), 10 modified.

> Implemented but not yet compiled/run (no JDK/Maven on PATH at the time) — build + smoke-test in IntelliJ before relying on it.

**Still open after this session:** `KEY` → `ANTHROPIC_API_KEY` env-var mismatch in `AppConfig`; MongoDB password passed in argv + not URL-encoded; no HTTP timeouts on the two older AI callers; `--port` defaults to 5432 for all engines; a backup artifact is committed under `backups/`; no unit tests.
