# Database Backup Utility CLI

A Java command-line tool for backing up and restoring **PostgreSQL, MySQL, and MongoDB**
databases — with streaming compression, an AI assistance layer, point-in-time restore, and
clean structured reporting.

![Java](https://img.shields.io/badge/Java-21-orange)
![Build](https://img.shields.io/badge/build-Maven-blue)
![Databases](https://img.shields.io/badge/databases-PostgreSQL%20·%20MySQL%20·%20MongoDB-informational)

---

## Features

- **Backup & restore** across PostgreSQL, MySQL, and MongoDB by driving the native client
  tools (`pg_dump` / `mysqldump` / `mongodump` and `psql` / `mysql` / `mongorestore`).
- **Streaming compression** — data is piped straight from the dump process through the
  compressor to disk, so even a large database is handled in **constant memory**. Pick GZIP,
  ZSTD, LZ4, or BZIP2.
- **AI assistance (Google Gemini, free tier)** with a built-in offline **mock mode** — no
  API key or network needed to try it:
  - **Root-cause analysis** of every run, success or failure.
  - **Compression advisor** that reads the real database size and recommends a strategy.
  - **Natural-language input** — describe the job in plain English instead of memorising flags.
  - **Restore guidance** — a short safety briefing before you overwrite live data.
- **Point-in-time restore** — choose a snapshot from an interactive list or restore a specific
  file; the compression format is auto-detected from the archive (with a magic-byte safety net).
- **Post-backup reports** with a trends section: archive-size history, delta vs. the previous
  run, growth/shrink warnings, and pruning hints.

---

## Architecture

```
com.backuputil/
├── cli/
│   └── BackupCommand.java        — picocli entry point, orchestrates the workflow
├── config/
│   ├── AppConfig.java            — API key + mock-mode singleton
│   └── DbConfig.java             — immutable DB connection config
├── model/
│   ├── BackupResult.java         — structured result of a backup run
│   ├── RestoreResult.java        — structured result of a restore run
│   ├── CompressionStrategy.java  — enum GZIP/BZIP2/LZ4/ZSTD + extension auto-detect
│   └── ParsedIntent.java         — output of natural-language parsing
├── service/
│   ├── DatabaseService.java      — engine interface
│   └── impl/
│       ├── PostgresService.java
│       ├── MysqlService.java
│       └── MongoService.java
├── ai/
│   ├── RootCauseAnalyser.java    — failure/success analysis
│   ├── CompressionAdvisor.java   — recommends + confirms a compression strategy
│   ├── NaturalLanguageParser.java— plain English → backup config
│   ├── RestoreAdvisor.java       — interactive point-in-time restore + guidance
│   └── BackupReportGenerator.java— human-readable report with trends
└── util/
    ├── GeminiClient.java         — single Gemini API client, shared by the ai/ classes
    ├── LlmResponse.java          — dependency-free response-text extractor
    ├── JsonStrings.java          — RFC-8259 JSON string escaping
    └── CompressionStreams.java   — magic-byte-aware decompression for restore

org.example/
└── Main.java                     — bootstraps the picocli command
```

---

## Design highlights

- **Deadlock-free process I/O.** A child process's `stdout` and `stderr` are drained
  concurrently on virtual threads, so a chatty `pg_dump` can't fill a fixed-size OS pipe
  buffer and hang the pipeline — the classic `Runtime.exec()` trap.
- **Constant-memory streaming.** `InputStream.transferTo` pumps the dump straight into the
  compressor and out to disk; the full archive is never held in RAM.
- **Credentials never on the command line.** Passwords are passed via the `PGPASSWORD` /
  `MYSQL_PWD` environment variables rather than process arguments, so they don't appear in the
  process list. The MongoDB connection string is URL-encoded so special characters in a
  password can't corrupt the URI.
- **Real, pluggable compression.** GZIP (JDK-native) plus ZSTD, LZ4, and BZIP2 via libraries,
  chosen by an advisor that queries the actual database size and backup frequency.
- **Safe restores.** The decompressor is selected from the file extension and confirmed by the
  archive's magic bytes; a destructive-operation gate requires explicit confirmation before any
  write to a live database.
- **Honest failure semantics.** Every run returns a structured result (status, size, duration,
  exit code, error message); a failed backup deletes its own incomplete archive instead of
  leaving a corrupt file behind.

---

## Getting started

### Prerequisites

- **JDK 21+** (the tool uses virtual threads).
- **Maven** — IntelliJ IDEA bundles both a JDK and Maven, so no separate install is needed there.
- The **native client tools** for the databases you use, available on your `PATH`:
  - PostgreSQL → `pg_dump`, `psql`
  - MySQL → `mysqldump`, `mysql`
  - MongoDB → `mongodump`, `mongorestore`

### Build

```bash
mvn clean package
```

…or **Build Project** in IntelliJ.

### Configure the AI layer (optional)

Set these as environment variables in your run configuration:

| Variable | Purpose |
|---|---|
| `GEMINI_API_KEY` | Google Gemini API key for real AI analysis/parsing. Free tier — get one at [aistudio.google.com/apikey](https://aistudio.google.com/apikey). |
| `MOCK_AI` | Set to `true` to use the free offline rule-based mock instead of real API calls. |

If neither is set, AI features are skipped and the core backup/restore still runs.

### Run

Run `org.example.Main` from IntelliJ (set **Program arguments**), or via `java -cp`.

```bash
# Flag-driven backup
-t postgres -H localhost -u postgres -d shop_db

# Natural-language backup
--nl "backup my postgres database called shop_db on localhost with user admin"

# Interactive point-in-time restore (pick a snapshot)
-t postgres -u postgres -d shop_db --restore

# Restore a specific archive
-t postgres -u postgres -d shop_db --restore-file ./backups/shop_db_20260709_224318_backup.sql.zst

# Backup and also write the report to a .txt file
-t postgres -u postgres -d shop_db --report
```

---

## Command-line options

| Option | Description | Default |
|---|---|---|
| `-t`, `--type` | Engine: `postgres` / `mysql` / `mongo` | prompted |
| `-H`, `--host` | Database host | `localhost` |
| `-p`, `--port` | Database port | per engine (5432 / 3306 / 27017) |
| `-u`, `--user` | Username | prompted |
| `-P`, `--password` | Password (entered interactively) | prompted |
| `-d`, `--database` | Database name | prompted |
| `-o`, `--output` | Output directory | `./backups` |
| `--backup-frequency` | Backups per day (informs compression advice) | `1` |
| `--nl "<text>"` | Describe the job in natural language | — |
| `--restore` | Interactive restore — pick a snapshot | off |
| `--restore-file <path>` | Restore a specific archive file | — |
| `--report` | Also write the post-backup report to a file | off |
| `--mock` | Bypass the connection check (for pipeline testing) | off |

---

## Compression strategies

| Strategy | Extension | Best for |
|---|---|---|
| **GZIP** | `.gz` | Maximum compatibility; always available (JDK-native) |
| **ZSTD** | `.zst` | Balanced speed/ratio — the default recommendation |
| **LZ4** | `.lz4` | Speed — large or frequently-run backups |
| **BZIP2** | `.bz2` | Maximum ratio — small, infrequent backups |

The advisor estimates the database size (`pg_database_size` / `information_schema` /
`dbStats`) and backup frequency, recommends a strategy with its reasoning, and lets you accept
or override it. On restore, the format is detected from the archive automatically.

---

## Testing

```bash
mvn test
```

JUnit 5 tests cover the pure helpers — response-text extraction (`LlmResponse`), JSON escaping
(`JsonStrings`), and compression-strategy detection (`CompressionStrategy`).

---

## Notes & limitations

- **Restore targets an empty database.** PostgreSQL restore replays a plain SQL script, so
  pre-existing objects with the same names will conflict — restore into a fresh database.
- **MongoDB credentials.** `mongodump` / `mongorestore` have no password environment variable,
  so the password is passed as a process argument for those two tools (PostgreSQL and MySQL use
  env vars). A config-file approach is the planned improvement.

---

## Roadmap

- **Encryption at rest** — AES-256-GCM applied to every archive, key derived from a passphrase
  via PBKDF2 (random salt + IV per file, stored in a file header). Designed; not yet implemented.
- **Cloud upload** — push archives to object storage (AWS S3), with AI-assisted storage-tier
  decisions (e.g. age-based transition to cold storage).
- **Password input masking** in terminals that echo interactive input.
