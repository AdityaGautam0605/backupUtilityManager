# PostgreSQL backup/restore benchmark

This opt-in harness measures the actual `PostgresService` backup and streaming restore paths.
It initializes its **own PostgreSQL cluster**, binds it to a free loopback port, and never connects
to existing databases. AI calls and interactive prompt delays are excluded.

## Run on Windows

Requires JDK 21+, Maven, and PostgreSQL server/client tools (`initdb`, `pg_ctl`, `pg_dump`, `psql`).
Run from PowerShell in the project root:

```powershell
# Small methodology trial: at least 100 MiB of uncompressed pg_dump output.
./scripts/benchmark-postgres.ps1 -PgBin 'D:/postgreSQL/bin' -JavaHome 'D:/openjdk25' `
  -Maven 'D:/IntelliJ IDEA Community Edition 2025.2.3/plugins/maven/lib/maven3/bin/mvn.cmd' `
  -TargetMiB 100 -Runs 3 -Warmups 1

# Resume-scale experiment: native baseline and ZSTD on at least 1 GiB of raw output.
./scripts/benchmark-postgres.ps1 -PgBin 'D:/postgreSQL/bin' -JavaHome 'D:/openjdk25' `
  -Maven 'D:/IntelliJ IDEA Community Edition 2025.2.3/plugins/maven/lib/maven3/bin/mvn.cmd' `
  -TargetMiB 1024 -Runs 3 -Warmups 1 -Methods 'NONE,ZSTD'
```

Replace tool paths for your installation. If Maven is on PATH and `JAVA_HOME` is set, omit those
two arguments. For a quick harness smoke test use `-TargetMiB 1 -Runs 1 -Warmups 0`.
Omit `-Methods` to test all five methods. A subset is useful after the small trial identifies
practical candidates; BZIP2 can take substantially longer. Never imply that an omitted method was
measured at the larger size.
Maven's `benchmark` profile compiles the harness and writes a dependency classpath file; normal
`mvn test` does not launch a benchmark. The first profile build may download a Maven plugin.

Allow several times the raw target size in free disk space for the source database, restore
target, PostgreSQL WAL, and archives. Runtime depends on hardware; larger BZIP2 runs can be slow.
The isolated cluster uses trust authentication on loopback for synthetic data only; use a trusted
local workstation. Server durability settings retain their defaults. The server is stopped in
the harness's `finally` block. If the Java process is forcibly killed, inspect `server.log` and
stop only that run's cluster using `pg_ctl -D '<run-directory>/cluster' -m fast -w stop`.

## Dataset and procedure

1. Generate 10,000 synthetic customers plus orders with primary/foreign keys, a unique constraint,
   an amount check, and an additional index. Orders contain amounts, dates, categorical statuses,
   repeated descriptive text, and varying deterministic MD5-derived hexadecimal payloads.
2. Grow the dataset until the **actual uncompressed SQL dump** reaches the requested threshold.
   Record its exact byte count and physical database size separately. The threshold can be slightly
   exceeded; `-TargetMiB 1024` does not mean exactly 1,073,741,824 bytes.
3. Capture source row counts, ordered SHA-256 data digests, and a schema digest covering columns,
   constraints, and indexes for the generated schema. No whole-table data is loaded into Java memory.
4. Compare native uncompressed `pg_dump` (`NONE`) with GZIP, ZSTD, LZ4, and BZIP2. Each method has
   the requested warm-ups and measured repetitions; order rotates between rounds.
5. Back up and restore using separate fresh JVM workers with a 128 MiB maximum heap. Each restore
   uses a freshly created database. Compare restored data and schema against the source snapshot.
6. Retain every sample in CSV, including warm-ups, but exclude warm-ups from summaries. Abort on
   any native-process failure or verification mismatch. A completed summary requires all checks
   to pass. Successfully verified archives and target databases are removed to conserve space;
   only targets in this new cluster and exact archive paths created by the run are removed.

The synthetic corpus mixes repetition and varying text; its compression ratio is **not** a claim
about production databases, images, encrypted data, or all database engines. The generator SQL is
saved with each result so the content distribution is reviewable and reproducible.

## Measurements

| Measurement | Definition |
|---|---|
| Raw reference bytes | Size of the uncompressed source SQL dump after dataset generation |
| Physical database bytes | `pg_database_size()`; includes storage overhead and is not the compression denominator |
| Storage reduction | `(1 - archive_bytes / raw_reference_bytes) * 100` |
| Compression ratio | `raw_reference_bytes / archive_bytes` |
| Backup time | Wall time inside the worker around the service/native dump operation |
| Restore time | Wall time around restore; compressed methods include both validation and replay decompression passes |
| Backup input throughput | Raw reference MiB divided by backup seconds |
| Sampled Java heap | Maximum observed heap-used value sampled every 10 ms during that operation |
| Correctness | Matching table row counts, SHA-256 data digests, and schema digest |

Important interpretation limits:

- Java worker startup, dataset generation, target database creation, and verification are excluded
  from operation timings. Native client startup and archive writes are included. Each operation
  runs in a fresh JVM, so warm-ups warm OS/database caches, **not** a persistent JVM's JIT compiler.
- The filesystem cache is not forcibly cleared. Run on an otherwise idle machine and preserve
  the environment metadata. Warm-up results are retained for transparency.
- The memory metric is **sampled Java heap**, not total Java RSS, off-heap/native compression memory,
  `pg_dump`/`psql` memory, or PostgreSQL server memory. Sampling can miss short-lived peaks.
  Do not claim a total-memory reduction from these numbers. A 128 MiB heap cap is a configuration,
  not a measurement of memory consumed.
- Uncompressed native restore is a baseline and does not perform the application's compression
  validation pass. A slower compressed restore may be the cost of validation plus decompression.
- Native dumps can contain changing metadata such as generated restriction tokens. Every method
  dumps the same unchanged source data; archives are not expected to be byte-for-byte identical.
- SHA-256 comparison verifies the generated fixture's rows and schema definitions, not every
  possible PostgreSQL object, permission, extension, or recovery scenario.
- These tests measure backup/archive processing; they do not shrink the live database. They
  do not measure crash durability or flush the destination archive with an explicit fsync.

## Output and reproducibility

Each run prints its unique `target/benchmarks/run-*` directory, containing:

- `status.txt`: `RUNNING`, `COMPLETE`, or `FAILED`.
- `results.csv`: individual samples with warm-up flags and verification results.
- `summary.csv`: per-method median/min/max times, median sizes/reduction, and sampled heap maxima.
- `environment.properties`: Java/PostgreSQL/OS versions, processor count, dataset size, and settings.
- `dataset.sql` and `source-verification.txt`: reproducible fixture and expected digests/counts.
- Per-operation logs, timing properties, verification records, and the stopped source cluster.

Copy the small result files into a named directory under `benchmarks/results/` before `mvn clean`,
which removes `target/`. Do not commit database clusters or SQL dump archives. Existing user
databases are outside the harness and are not modified.

## Resume wording after a completed run

Use actual numbers from a completed, verified `summary.csv`:

> Benchmarked a Java PostgreSQL backup CLI on a **[actual raw GiB] GiB synthetic SQL dump**,
> reducing archive size by **[median reduction]% with ZSTD** across three measured runs;
> verified restored rows and schema using SHA-256 digests and row counts.

Add the median backup time only with its machine/dataset context available in this report. Do
not extrapolate 100 MiB timings to 1 GiB, compare compression methods using their compressed-output
throughput as if it were source throughput, or invent a performance improvement versus an untested
version. Report a tradeoff if a smaller archive takes longer to produce.
