# Database Backup Utility CLI

A Java command-line tool for backing up and restoring **PostgreSQL, MySQL, and MongoDB**
databases — with streaming compression, optional AI assistance, snapshot restore, and
clean structured reporting.

![Java](https://img.shields.io/badge/Java-21-orange)
![Build](https://img.shields.io/badge/build-Maven-blue)
![Databases](https://img.shields.io/badge/databases-PostgreSQL%20·%20MySQL%20·%20MongoDB-informational)

---

## Features

- **Backup & restore** across PostgreSQL, MySQL, and MongoDB by driving the native client
  tools (`pg_dump` / `mysqldump` / `mongodump` and `psql` / `mysql` / `mongorestore`).
- **Streaming compression** — data is piped straight from the dump process through the
  compressor to disk without loading the full dump into memory. Pick GZIP,
  ZSTD, LZ4, or BZIP2.
- **AI assistance (OpenAI Responses API)** with a built-in offline **mock mode** — no
  API key or network needed to try it:
  - **Backup result analysis** after successful or failed native backup attempts.
  - **Natural-language input** — describe the job in plain English instead of memorising flags.
  - **Restore guidance** — a short briefing before replaying an archive into a database.
- **Rule-based compression advisor** — recommends a strategy using database size and the supplied
  backup frequency; this advisor does not call an AI model or schedule backups.
- **Snapshot restore** — choose an existing archive from an interactive list or restore a specific
  file. Compression is selected by extension, with a GZIP magic-byte fallback for older mislabeled
  archives. This is not transaction-log-based point-in-time recovery.
- **Post-backup reports** with archive-size history, comparison against an older archive with
  the same format/compression extension, size-change notices, and pruning hints. Failed runs
  do not report a size delta; successful command completion is not presented as proof of recovery.

---

## Architecture

Built with **Java 21**, **Maven**, **picocli**, **JDBC**, the **MongoDB Java driver**, and **JUnit 5**.
Compression uses the JDK, Apache Commons Compress, lz4-java, and zstd-jni. The AI client uses
Java's HTTP client to call the OpenAI Responses API.

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
│   ├── RestoreAdvisor.java       — snapshot selection, confirmation, and restore guidance
│   └── BackupReportGenerator.java— human-readable report with trends
└── util/
    ├── OpenAiClient.java         — OpenAI Responses API client, shared by the ai/ classes
    ├── LlmResponse.java          — response-text extraction (OpenAI plus legacy helpers)
    ├── JsonStrings.java          — RFC-8259 JSON string escaping
    ├── ConsoleInput.java         — consistent line input and terminal password masking
    └── CompressionStreams.java   — magic-byte-aware decompression for restore

org.example/
└── Main.java                     — bootstraps the picocli command
```

---

## Design highlights

- **Concurrent process I/O.** A child process's `stdout` and `stderr` are drained
  concurrently on virtual threads, so a chatty `pg_dump` can't fill a fixed-size OS pipe
  buffer and hang the pipeline — the classic `Runtime.exec()` trap.
- **Streaming archive data.** Backup data is streamed into compression; restore uses bounded
  buffers and caps diagnostic capture at 64 KiB. Backup-side diagnostic capture is not yet bounded,
  so the entire application's memory use is not guaranteed constant for arbitrarily verbose clients.
- **PostgreSQL/MySQL credentials via environment.** Passwords are passed via the `PGPASSWORD` /
  `MYSQL_PWD` environment variables rather than process arguments, so they don't appear in the
  process list. The MongoDB connection string is URL-encoded so special characters in a
  password can't corrupt the URI. MongoDB native tools still receive a password argument.
- **Real, pluggable compression.** GZIP (JDK-native) plus ZSTD, LZ4, and BZIP2 via libraries,
  chosen by an advisor that queries the actual database size and backup frequency.
- **Safe restores.** The decompressor is selected from the file extension and confirmed by the
  GZIP magic-byte fallback. A validation pass decompresses and discards the output before a
  database client starts, rejecting empty payloads and detected compression corruption. A second
  pass streams decompressed bytes directly into the client without staging files. A
  destructive-operation gate requires explicit confirmation before any write to a live database.
  PostgreSQL restores stop on SQL errors and run in one transaction; MongoDB restores filter to
  the selected database's namespaces and stop on errors.
- **Failure reporting.** Native backup/restore attempts return structured results (status, size,
  duration, exit code, error message). Failed backups attempt to delete incomplete archives and
  warn if cleanup fails. Early connection failures return a nonzero CLI exit code.
- **Consistent interactive input.** Passwords are requested once after resolving database settings,
  with or without `-P`. Terminal input is masked when supported; other consoles display an echo
  notice. Input is read without consuming answers intended for later prompts.

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

Run `org.example.Main` from IntelliJ. Open **Run → Edit Configurations**, select the application,
and replace **Program arguments** with one of the examples below. These are application arguments,
not standalone shell commands. Use **Modify options → Program arguments** if the field is hidden.
Set the working directory to the project root to keep `./backups` inside the project.

The role and database must already exist; substitute your own names. The CLI does not create
database roles or databases. The packaged JAR is not currently a standalone executable with
dependencies; command-line launching requires a complete Java classpath.

```bash
# Flag-driven backup
-t postgres -H localhost -u postgres -d shop_db

# Natural-language backup
--nl "backup my postgres database called shop_db on localhost with user postgres"

# Interactive snapshot restore (pick an existing archive)
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
| `-P`, `--password` | Optional prompt flag; the password is requested once, even when this flag is omitted | prompted |
| `-d`, `--database` | Database name | prompted |
| `-o`, `--output` | Output directory | `./backups` |
| `--backup-frequency` | Backups per day (informs compression advice) | `1` |
| `--nl "<text>"` | Describe the job in natural language | — |
| `--restore` | Interactive restore — pick a snapshot | off |
| `--restore-file <path>` | Restore a specific archive file | — |
| `--report` | Also write the post-backup report to a file | off |
| `--mock` | Bypass a failed connection check; still runs real native database commands | off |

Passwords are requested after the database settings are resolved. Real terminals use masked
password input. IntelliJ and other consoles without `System.console()` may echo typed characters;
the CLI displays a notice in that case. End-of-input cancels before connecting; a blank password
is passed through unchanged for databases configured to permit it. `-P` accepts no password value.

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
target restrictions, cancellation, source changes, password prompts, report comparisons, OpenAI
request/response handling, and streaming without temporary storage. A subprocess test streams a
64 MiB decompressed payload to a simulated client with a 32 MiB Java heap and a nonexistent
temporary directory. This tests the Java streaming path, not database-server memory or throughput.
Unit tests use controlled child-process substitutes and do not connect to existing databases.

For real PostgreSQL recovery tests, set `RESTORE_TEST_PG_BIN` to a directory containing `initdb`,
`pg_ctl`, `pg_dump`, and `psql`, and add that directory to `PATH`, then run `mvn clean test`.
The tests initialize a separate local cluster on an available loopback port, check a dump/restore
round trip and rollback on SQL errors, and stop the cluster afterwards. Test data and logs remain
under `target/restore-pg-test-*`. Without that environment variable, these integration tests are
skipped. They do not use the credentials or databases configured for normal CLI runs.

### Validation status — September 28, 2026

| Check | Evidence / status |
|---|---|
| Latest default Maven test run | 59 test cases discovered: 56 passed, 3 PostgreSQL integration tests skipped; no failures/errors |
| Isolated PostgreSQL integration suite | Previously passed all 3 tests: backup/restore round trip, SQL-error rollback, corrupt-archive rejection |
| Manual PostgreSQL sample | Restored a table with 3 rows into a separate database and verified the rows with SQL |
| MySQL | Previously used successfully; the revised restore path still needs a live round-trip test |
| MongoDB | Implementation and restore-command unit tests present; live recovery remains unverified |
| OpenAI | Request/response behavior tested offline; local live requests encountered HTTP 429 |

These are observed checks, not a production-readiness or disaster-recovery guarantee.

### Reproduce a PostgreSQL sample round trip

Use an existing PostgreSQL login and a disposable demo database. In `psql`, create a small source:

```sql
CREATE DATABASE backup_cli_demo;
\c backup_cli_demo
CREATE TABLE public.demo_athletes (id INTEGER PRIMARY KEY, name TEXT NOT NULL, sport TEXT NOT NULL);
INSERT INTO public.demo_athletes VALUES
    (1, 'Aarav', 'Cricket'), (2, 'Meera', 'Badminton'), (3, 'Kabir', 'Football');
```

Run the application with these program arguments (substitute your role if needed):

```text
-t postgres -H localhost -p 5432 -u postgres -d backup_cli_demo -P --report
```

Create an empty target in `psql`:

```sql
CREATE DATABASE backup_cli_demo_restored;
```

Replace the archive placeholder below with the actual output path from the backup, then run and
confirm the restore:

```text
-t postgres -H localhost -p 5432 -u postgres -d backup_cli_demo_restored -P --restore-file "<actual-backup-path>"
```

Verify the target in `psql`:

```sql
\c backup_cli_demo_restored
SELECT * FROM public.demo_athletes ORDER BY id;
```

Expect all three original rows. Repeated restores into the same populated target can conflict;
use a fresh target for each test. Set `MOCK_AI=true` to perform this real database test without
external AI calls. Do not add `--mock`: it bypasses failed connection checks and is not a dry run.

## Recent changes — September 27–28, 2026

- Centralized restore execution in `NativeRestore`: PostgreSQL error-stop/transaction options,
  MongoDB namespace filtering, and MySQL batch execution with local defaults disabled.
- Added full decompression validation before launching a restore client, then replaced temporary
  decompressed-file staging with two-pass streaming from a shared, locked file handle.
- Added source size/timestamp checks and SHA-256 comparison between passes, bounded restore
  diagnostics, error propagation, and process cleanup on cancellation or stream failure.
- Updated restore selection to match database names case-sensitively and separate SQL/BSON formats.
- Migrated Gemini calls to OpenAI Responses API; added `OPENAI_MODEL`, offline mock behavior,
  completed-response parsing, and failure-path tests. Keys come from the environment.
- Fixed password prompting when `-P` is omitted, removed the misleading duplicate-prompt notice,
  and consolidated console input across connection, compression, and restore prompts.
- Corrected reports to suppress failed-run deltas, select older comparable archives, show useful
  small-file throughput, and avoid declaring backup health based on archive size alone.
- Added regression tests and a reproducible PostgreSQL recovery workflow.

---

## Reproducible performance benchmark

The [PostgreSQL benchmark guide](benchmarks/README.md) describes an isolated synthetic-data
experiment comparing native uncompressed `pg_dump`, GZIP, ZSTD, LZ4, and BZIP2. The
[PowerShell launcher](scripts/benchmark-postgres.ps1) supports a 100 MiB trial and a 1 GiB run,
warm-ups, repeated measurements, and CSV exports. Every restore is verified against source row
counts, SHA-256 data digests, and schema definitions. No existing database is used.

Results distinguish raw dump size from physical database size, include the two-pass restore cost,
and report sampled Java heap rather than claiming total-process memory measurements. Performance
claims must use completed runs and their recorded dataset/environment; do not extrapolate trial
results to larger databases.

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

- **Archive identity and collision prevention** — record source engine/host metadata and avoid
  overwriting archives when matching backups start within the same second.
- **Broader recovery verification** — live MySQL/MongoDB round trips and larger representative datasets.
- **Operational reliability** — native-process timeouts, bounded backup diagnostics, scheduling,
  retention policies, and automated archive verification.
- **Distribution and CI** — Maven wrapper, a runnable distribution, and automated build/test checks.

- **Encryption at rest** — AES-256-GCM applied to every archive, key derived from a passphrase
  via PBKDF2 (random salt + IV per file, stored in a file header). Planned; not implemented.
- **Cloud upload** — push archives to object storage (AWS S3), with AI-assisted storage-tier
  decisions (e.g. age-based transition to cold storage).
- **IDE password input masking** — real terminal masking is supported; IDE console input may echo.
