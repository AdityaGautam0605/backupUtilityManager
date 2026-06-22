# Database Backup Utility CLI

A Java CLI tool for backing up PostgreSQL, MySQL, and MongoDB databases — with an AI layer for root cause analysis, compression strategy advice, and natural language input parsing.

---

## Current Architecture

```
com.backuputil/
├── ai/
│   ├── RootCauseAnalyser.java      — AI-powered failure/success analysis
│   ├── CompressionAdvisor.java     — recommends + confirms compression strategy
│   └── NaturalLanguageParser.java  — parses plain English into backup config
├── cli/
│   └── BackupCommand.java          — picocli entrypoint, wires everything together
├── config/
│   ├── AppConfig.java              — API key + mock mode singleton
│   └── DbConfig.java               — immutable DB connection config
├── model/
│   ├── BackupResult.java           — structured result of a backup run
│   ├── CompressionStrategy.java    — enum: GZIP, BZIP2, LZ4, ZSTD
│   └── ParsedIntent.java           — output of natural language parsing
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

---

## What's Pending

### Phase 4 — Restore + Report (not started)
- `RestoreAdvisor` — AI-guided interactive restore (point-in-time selection, decompression strategy auto-detection from file extension)
- `BackupReportGenerator` — human-readable post-backup summary with trends and recommendations
- All three services currently have `restore()` as a stub (`"...pending implementation"`)

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

### Known Outstanding Issues
- `Main.java` still sits in `org.example` package — should be moved to `com.backuputil` for consistency
- Password masking in terminal input — currently picocli's `interactive=true` echoes the password in plaintext in some terminal contexts (e.g. IntelliJ Run window). A more robust fix using `System.console().readPassword()` was designed but deliberately skipped for now as a low-priority cosmetic issue
- BZIP2/LZ4/ZSTD Maven dependencies need correct group IDs verified before compression actually uses anything other than GZIP fallback (current `pom.xml` entries had incorrect coordinates for LZ4 — needs fixing: likely `net.jpountz.lz4:lz4:1.3.0` rather than `org.lz4:lz4-java`)
- No unit tests yet anywhere in the project
- No `restore()` implementation in any of the three services

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
3. Phase 4 — Restore + Report
4. Move `Main.java` to `com.backuputil` package
5. Fix compression library Maven coordinates (BZIP2/LZ4/ZSTD)
6. Add unit tests
7. Phase 5 — Cloud Upload (S3) once budget allows
8. Write a proper architecture diagram for the README (for resume/portfolio presentation)
