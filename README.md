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
- **AI assistance (OpenAI Responses API)** with a built-in offline **mock mode** — no
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
│   ├── NativeRestore.java        — archive validation and shared native restore lifecycle
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
    ├── OpenAiClient.java         — OpenAI Responses API client, shared by the ai/ classes
    ├── LlmResponse.java          — response-text extraction (OpenAI plus legacy helpers)
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
  GZIP magic-byte fallback. A validation pass decompresses and discards the output before a
  database client starts, rejecting empty payloads and detected compression corruption. A second
  pass streams decompressed bytes directly into the client without staging files. A
  destructive-operation gate requires explicit confirmation before any write to a live database.
  PostgreSQL restores stop on SQL errors and run in one transaction; MongoDB restores filter to
  the selected database's namespaces and stop on errors.
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
| `OPENAI_API_KEY` | Your OpenAI API key for real AI analysis/parsing; set it in your terminal or IntelliJ run environment. |
| `OPENAI_MODEL` | Optional Responses API model override; defaults to `gpt-4.1-mini`. |
| `MOCK_AI` | Set to `true` to use the free offline rule-based mock instead of real API calls. |

Without an API key or mock mode, AI features are disabled and core backup/restore still runs.
`OPENAI_MODEL` alone does not enable AI. The old `GEMINI_API_KEY` setting is no longer used.
Set `MOCK_AI=false` when you want real requests. Do not put API keys in source files or commits.
In IntelliJ, add `OPENAI_API_KEY` under **Run → Edit Configurations → Environment variables**.
Restart the application after changing environment settings.

The client uses [OpenAI's Responses API](https://developers.openai.com/api/docs/guides/text),
Bearer authentication, and `store: false`. Completed assistant text is extracted from the
response's `output` array; failed, incomplete, or refused responses use the existing fallback paths.

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

JUnit 5 tests cover response-text extraction, JSON escaping, compression detection, restore
selection, all four decompression formats, corrupt/empty archives, native error propagation,
target restrictions, cancellation, source changes, and streaming without temporary storage.
A subprocess test restores a 64 MiB payload with a 32 MiB heap and a nonexistent temporary
directory. Unit tests use controlled child
process substitutes and do not connect to existing databases.

For real PostgreSQL recovery tests, set `RESTORE_TEST_PG_BIN` to a directory containing `initdb`,
`pg_ctl`, `pg_dump`, and `psql`, and add that directory to `PATH`, then run `mvn clean test`.
The tests initialize a separate local cluster on an available loopback port, check a dump/restore
round trip and rollback on SQL errors, and stop the cluster afterwards. Test data and logs remain
under `target/restore-pg-test-*`. Without that environment variable, these integration tests are
skipped. They do not use the credentials or databases configured for normal CLI runs.

---

## Notes & limitations

- **Restore targets an empty database.** PostgreSQL restore replays a plain SQL script, so
  pre-existing objects with the same names will conflict — restore into a fresh database.
- **Restore storage and cost.** Restore writes no temporary archive or decompressed file. It reads
  and decompresses the source twice: once for validation, then again while streaming to the client.
  Buffers and SHA-256 digests keep memory bounded independently of archive size. Database files,
  transaction logs, and the database client's own storage requirements still apply.
- **Keep the archive unchanged during restore.** Both passes share one open file handle and a
  shared file lock. Size/timestamp checks and a SHA-256 comparison detect changes; the client is
  stopped on a detected change or stream failure before stdin is closed. Filesystem locks may be
  advisory, so this is not an immutable snapshot: concurrent writes or second-pass I/O failures
  can still leave partial changes in MySQL/MongoDB. Restore requires a seekable file with shared
  locking support; stdin/FIFO sources are not supported.
- **Rollback limits.** PostgreSQL uses `psql -X --set=ON_ERROR_STOP=on --single-transaction --file=-`
  for the plain dumps produced by this tool. Arbitrary scripts containing their own transaction
  control, connection changes, or commands forbidden in a transaction are not supported.
  MySQL and MongoDB can leave partial changes after a native restore failure; they are not atomic.
- **MongoDB scope.** Restore filters to `<selected-database>.*`; it does not rename databases or
  drop existing collections. Generated archives naming a different source database are rejected.
  Use the original database name, including its case. For externally named archives, ensure that
  database's namespaces actually exist in the archive; namespace filtering does not remap them.
  MongoDB restore currently accepts database names containing letters, digits, `_`, and `-`.
- **Archive provenance.** The picker separates SQL and MongoDB archives, but legacy SQL filenames
  cannot distinguish PostgreSQL from MySQL or identify a source host. Use a separate backup
  directory per source and restore only trusted dumps from the selected engine. Compression
  validation does not prove SQL/BSON correctness or completeness; the native client checks content.
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
