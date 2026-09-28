package com.backuputil.benchmark;

import com.backuputil.config.DbConfig;
import com.backuputil.model.*;
import com.backuputil.service.impl.PostgresService;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

/** Opt-in harness. Owns an isolated cluster; never connects to an existing database. */
public final class PostgresBenchmark {
    private static final List<String> METHODS = List.of("NONE", "GZIP", "ZSTD", "LZ4", "BZIP2");
    private static List<String> selectedMethods = METHODS;
    private static final String SOURCE = "bench_source";
    private static Path root, bin, data;
    private static int port;
    private static long baseline;
    private static final List<Sample> samples = new ArrayList<>();

    record Sample(String method, int run, long archive, double backup, double restore,
                  long backupHeap, long restoreHeap) {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("worker")) { worker(args); return; }
        if (args.length < 4 || args.length > 5) throw new IllegalArgumentException("Expected: pg-bin target-MiB runs warmups [comma-separated-methods]");
        if (args.length == 5) {
            selectedMethods = Arrays.stream(args[4].split(",")).map(String::trim).map(s -> s.toUpperCase(Locale.ROOT)).toList();
            if (selectedMethods.isEmpty() || !METHODS.containsAll(selectedMethods)
                    || new HashSet<>(selectedMethods).size() != selectedMethods.size())
                throw new IllegalArgumentException("Choose unique methods from " + METHODS);
        }
        bin = Path.of(args[0]).toAbsolutePath();
        int mib = Integer.parseInt(args[1]), runs = Integer.parseInt(args[2]), warmups = Integer.parseInt(args[3]);
        if (mib < 1 || mib > 16384 || runs < 1 || runs > 20 || warmups < 0 || warmups > 5)
            throw new IllegalArgumentException("Invalid benchmark size or repetition count");
        Files.createDirectories(Path.of("target", "benchmarks"));
        root = Files.createTempDirectory(Path.of("target", "benchmarks").toAbsolutePath(), "run-");
        data = root.resolve("cluster");
        System.out.println("Benchmark directory: " + root);
        Files.writeString(root.resolve("status.txt"), "RUNNING\n");
        try {
            try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
                port = socket.getLocalPort();
            }
            tool("initdb", "-D", data.toString(), "-U", "benchmark", "--auth=trust", "--encoding=UTF8", "--no-locale");
            // Default durability settings are retained. Trust authentication is loopback-only and
            // limited to this disposable cluster; do not run on an untrusted shared machine.
            tool("pg_ctl", "-D", data.toString(), "-l", root.resolve("server.log").toString(),
                    "-o", "-h 127.0.0.1 -p " + port, "-w", "start");
            execute("postgres", "CREATE DATABASE " + SOURCE);
            String setup = """
                    CREATE SCHEMA bench;
                    CREATE TABLE bench.customers (
                      id integer PRIMARY KEY, name text NOT NULL, email text UNIQUE NOT NULL, region text NOT NULL);
                    INSERT INTO bench.customers SELECT i, 'Customer ' || i, md5(i::text) || '@example.test',
                      (ARRAY['north','south','east','west'])[1 + i % 4] FROM generate_series(1,10000) i;
                    CREATE TABLE bench.orders (
                      id bigint PRIMARY KEY, customer_id integer NOT NULL REFERENCES bench.customers(id),
                      created_at timestamp NOT NULL, amount numeric(12,2) NOT NULL CHECK(amount >= 0),
                      status text NOT NULL, notes text NOT NULL, payload text NOT NULL);
                    CREATE INDEX orders_customer_idx ON bench.orders(customer_id);
                    """;
            Files.writeString(root.resolve("dataset.sql"), setup);
            execute(SOURCE, setup);
            long rows = 0, target = mib * 1024L * 1024;
            Path reference = root.resolve("reference.sql");
            do {
                long batch = rows == 0 ? Math.max(100, target / 800) : Math.max(100, (long)Math.ceil((target - baseline) * (double)rows / baseline * 1.02));
                String insert = """
                        INSERT INTO bench.orders
                        SELECT i, 1 + i %% 10000, timestamp '2024-01-01' + (i %% 31536000) * interval '1 second',
                          ((i * 7919) %% 10000000)::numeric / 100,
                          (ARRAY['pending','paid','shipped','returned'])[1 + i %% 4],
                          'Synthetic order record; standard delivery; customer preferences and fulfilment tracking. '
                            || 'Reference ' || i || '; product group ' || i %% 250,
                          (SELECT string_agg(md5(i::text || ':' || j::text), '' ORDER BY j) FROM generate_series(1,16) j)
                        FROM generate_series(%d::bigint,%d::bigint) i;
                        """.formatted(rows + 1, rows + batch);
                Files.writeString(root.resolve("dataset.sql"), insert, StandardOpenOption.APPEND);
                execute(SOURCE, insert);
                rows += batch;
                nativeDump(SOURCE, reference);
                baseline = Files.size(reference);
                System.out.printf(Locale.ROOT, "Dataset: %,d orders; raw dump %.2f MiB (target %d MiB)%n", rows, baseline / 1048576.0, mib);
            } while (baseline < target);
            execute(SOURCE, "VACUUM ANALYZE");
            Snapshot expected = snapshot(SOURCE);
            Files.writeString(root.resolve("source-verification.txt"), expected.toString());
            long dbBytes = scalar(SOURCE, "SELECT pg_database_size(current_database())");
            Properties metadata = new Properties();
            metadata.setProperty("created_utc", Instant.now().toString());
            metadata.setProperty("java", System.getProperty("java.version"));
            metadata.setProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
            metadata.setProperty("available_processors", "" + Runtime.getRuntime().availableProcessors());
            metadata.setProperty("processor_identifier", System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unknown"));
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os)
                metadata.setProperty("physical_memory_bytes", "" + os.getTotalMemorySize());
            metadata.setProperty("postgres", queryOne(SOURCE, "SELECT version()"));
            metadata.setProperty("reference_raw_dump_bytes", "" + baseline);
            metadata.setProperty("database_physical_bytes", "" + dbBytes);
            metadata.setProperty("orders", "" + rows);
            metadata.setProperty("customers", "10000");
            metadata.setProperty("runs_per_method", "" + runs);
            metadata.setProperty("warmups_per_method", "" + warmups);
            metadata.setProperty("methods", String.join(",", selectedMethods));
            metadata.setProperty("worker_heap_limit", "128 MiB");
            metadata.setProperty("memory_metric", "Java heap used sampled every 10 ms during operation; not RSS, native client or database memory");
            metadata.setProperty("methodology", "synthetic mixed text/hex dataset; sequential rotated method order; fresh JVM per operation; warm filesystem caches; no AI; default PostgreSQL durability");
            store(metadata, root.resolve("environment.properties"));
            Files.delete(reference);
            Files.writeString(root.resolve("results.csv"),
                    "method,round,warmup,raw_reference_bytes,archive_bytes,reduction_pct,compression_ratio,backup_seconds,restore_seconds,backup_input_mib_per_sec,backup_sampled_heap_bytes,restore_sampled_heap_bytes,verified\n");
            for (int round = 0; round < warmups + runs; round++) {
                List<String> order = new ArrayList<>(selectedMethods);
                Collections.rotate(order, -round);
                boolean warmup = round < warmups;
                for (String method : order) {
                    System.out.println((warmup ? "Warm-up " : "Measured ") + (round + 1) + ": " + method);
                    Path dir = Files.createDirectory(root.resolve("round-" + round + "-" + method));
                    String targetDb = "bench_target_" + round + "_" + method.toLowerCase(Locale.ROOT);
                    execute("postgres", "CREATE DATABASE " + targetDb);
                    Properties backup = launchWorker("backup", method, SOURCE, dir, "");
                    Path archive = Path.of(backup.getProperty("archive"));
                    Properties restore = launchWorker("restore", method, targetDb, dir, archive.toString());
                    Snapshot actual = snapshot(targetDb);
                    boolean verified = expected.equals(actual);
                    Files.writeString(dir.resolve("verification.txt"), "verified=" + verified + "\n" + actual);
                    double backupSeconds = Double.parseDouble(backup.getProperty("seconds"));
                    double restoreSeconds = Double.parseDouble(restore.getProperty("seconds"));
                    long archiveBytes = Files.size(archive);
                    long backupHeap = Long.parseLong(backup.getProperty("sampled_heap_bytes"));
                    long restoreHeap = Long.parseLong(restore.getProperty("sampled_heap_bytes"));
                    String row = String.format(Locale.ROOT, "%s,%d,%s,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%d,%s%n",
                            method, warmup ? round + 1 : round - warmups + 1, warmup, baseline, archiveBytes,
                            reduction(baseline, archiveBytes), (double)baseline / archiveBytes,
                            backupSeconds, restoreSeconds, baseline / 1048576.0 / backupSeconds, backupHeap, restoreHeap, verified);
                    Files.writeString(root.resolve("results.csv"), row, StandardOpenOption.APPEND);
                    if (!verified) throw new IOException("Restore verification failed for " + method + "; artifacts retained");
                    if (!warmup) samples.add(new Sample(method, round, archiveBytes, backupSeconds, restoreSeconds, backupHeap, restoreHeap));
                    // These names exist only in our newly initialized cluster; no user database is dropped.
                    execute("postgres", "DROP DATABASE " + targetDb);
                    Files.delete(archive); // exact archive in this run; keep measurements and logs
                }
            }
            summary();
            Files.writeString(root.resolve("status.txt"), "COMPLETE\n");
            System.out.println("Verified results: " + root.resolve("summary.csv"));
        } catch (Throwable failure) {
            Files.writeString(root.resolve("status.txt"), "FAILED: " + failure + "\n");
            throw failure;
        } finally {
            if (Files.exists(data.resolve("postmaster.pid"))) {
                tool("pg_ctl", "-D", data.toString(), "-m", "fast", "-w", "stop");
            }
        }
    }

    private static void worker(String[] args) throws Exception {
        // worker action method database output-dir archive port
        port = Integer.parseInt(args[6]);
        String action = args[1], method = args[2], db = args[3];
        Path dir = Path.of(args[4]);
        DbConfig config = new DbConfig("127.0.0.1", port, "benchmark", "", db, false);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong peak = new AtomicLong();
        Runnable sample = () -> peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max);
        Thread sampler = Thread.ofVirtual().start(() -> {
            while (running.get()) { sample.run(); try { Thread.sleep(10); } catch (InterruptedException e) { break; } }
        });
        Properties result = new Properties();
        long start = System.nanoTime();
        try {
            if (action.equals("backup")) {
                Path archive;
                if (method.equals("NONE")) { archive = dir.resolve("native.sql"); nativeDump(db, archive); }
                else {
                    BackupResult backup = new PostgresService().backup(config, dir.toString(), CompressionStrategy.valueOf(method));
                    if (backup.getStatus() != BackupResult.Status.SUCCESS) throw new IOException(backup.getErrorMessage());
                    archive = Path.of(backup.getOutputPath());
                }
                result.setProperty("archive", archive.toAbsolutePath().toString());
            } else if (method.equals("NONE")) {
                ProcessBuilder pb = new ProcessBuilder("psql", "-X", "--no-password", "--set=ON_ERROR_STOP=on", "--single-transaction", "--file=-",
                        "-h", "127.0.0.1", "-p", "" + port, "-U", "benchmark", "-d", db);
                pb.redirectInput(Path.of(args[5]).toFile());
                run(pb, dir.resolve("native-restore.log"));
            } else {
                RestoreResult restored = new PostgresService().restore(config, args[5]);
                if (restored.getStatus() != RestoreResult.Status.SUCCESS) throw new IOException(restored.getErrorMessage());
            }
            result.setProperty("seconds", "" + ((System.nanoTime() - start) / 1e9));
        } finally { sample.run(); running.set(false); sampler.interrupt(); sampler.join(); }
        result.setProperty("sampled_heap_bytes", "" + peak.get());
        store(result, dir.resolve(action + ".properties"));
    }

    private static Properties launchWorker(String action, String method, String db, Path dir, String archive) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        run(new ProcessBuilder(java, "-Xmx128m", "-cp", System.getProperty("java.class.path"),
                PostgresBenchmark.class.getName(), "worker", action, method, db, dir.toString(), archive, "" + port), dir.resolve(action + ".log"));
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(dir.resolve(action + ".properties"))) { properties.load(input); }
        return properties;
    }

    private static void nativeDump(String db, Path destination) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("pg_dump", "-h", "127.0.0.1", "-p", "" + port, "-U", "benchmark", "-Fp", db);
        pb.environment().put("PGPASSWORD", "");
        pb.redirectOutput(destination.toFile());
        pb.redirectError(destination.resolveSibling("dump-errors.log").toFile());
        Process child = pb.start();
        await(child, destination.resolveSibling("dump-errors.log"));
    }

    private static Connection connect(String db) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port + "/" + db, "benchmark", "");
    }
    private static void execute(String db, String sql) throws SQLException {
        try (Connection c = connect(db); Statement s = c.createStatement()) { s.execute(sql); }
    }
    private static String queryOne(String db, String sql) throws SQLException {
        try (Connection c = connect(db); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { r.next(); return r.getString(1); }
    }
    private static long scalar(String db, String sql) throws SQLException { return Long.parseLong(queryOne(db, sql)); }

    record Snapshot(long customers, String customerHash, long orders, String orderHash, String schemaHash) {}
    private static Snapshot snapshot(String db) throws Exception {
        String customers = digestQuery(db, "SELECT row_to_json(t)::text FROM (SELECT * FROM bench.customers ORDER BY id) t");
        String orders = digestQuery(db, "SELECT row_to_json(t)::text FROM (SELECT * FROM bench.orders ORDER BY id) t");
        String schema = digestQuery(db, """
                SELECT description FROM (
                  SELECT 'column:' || table_name || ':' || ordinal_position || ':' || column_name || ':' || data_type || ':' || is_nullable
                    || ':' || coalesce(column_default,'') || ':' || coalesce(numeric_precision::text,'') || ':' || coalesce(numeric_scale::text,'') AS description
                    FROM information_schema.columns WHERE table_schema='bench'
                  UNION ALL SELECT 'constraint:' || c.relname || ':' || con.conname || ':' || pg_get_constraintdef(con.oid)
                    FROM pg_constraint con JOIN pg_class c ON c.oid=con.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='bench'
                  UNION ALL SELECT 'index:' || tablename || ':' || indexname || ':' || indexdef FROM pg_indexes WHERE schemaname='bench'
                ) definitions ORDER BY description
                """);
        return new Snapshot(scalar(db, "SELECT count(*) FROM bench.customers"), customers,
                scalar(db, "SELECT count(*) FROM bench.orders"), orders, schema);
    }
    private static String digestQuery(String db, String query) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (Connection c = connect(db)) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.setFetchSize(512);
                try (ResultSet r = s.executeQuery(query)) {
                    while (r.next()) { digest.update(r.getString(1).getBytes(StandardCharsets.UTF_8)); digest.update((byte)'\n'); }
                }
            }
            c.rollback();
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static double reduction(long raw, long compressed) { return (1.0 - (double)compressed / raw) * 100; }
    static double median(double[] values) {
        double[] copy = values.clone(); Arrays.sort(copy);
        int middle = copy.length / 2;
        return copy.length % 2 == 0 ? (copy[middle - 1] + copy[middle]) / 2 : copy[middle];
    }
    private static void summary() throws IOException {
        StringBuilder csv = new StringBuilder("method,runs,raw_reference_bytes,median_archive_bytes,median_reduction_pct,median_backup_seconds,min_backup_seconds,max_backup_seconds,median_restore_seconds,min_restore_seconds,max_restore_seconds,median_backup_input_mib_per_sec,max_backup_sampled_heap_bytes,max_restore_sampled_heap_bytes,all_verified\n");
        for (String method : selectedMethods) {
            List<Sample> group = samples.stream().filter(s -> s.method.equals(method)).toList();
            double[] backups = group.stream().mapToDouble(Sample::backup).toArray();
            double[] restores = group.stream().mapToDouble(Sample::restore).toArray();
            double archive = median(group.stream().mapToDouble(Sample::archive).toArray());
            csv.append(String.format(Locale.ROOT, "%s,%d,%d,%.0f,%.4f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%d,true%n",
                    method, group.size(), baseline, archive, (1 - archive / baseline) * 100,
                    median(backups), Arrays.stream(backups).min().orElseThrow(), Arrays.stream(backups).max().orElseThrow(),
                    median(restores), Arrays.stream(restores).min().orElseThrow(), Arrays.stream(restores).max().orElseThrow(),
                    median(group.stream().mapToDouble(s -> baseline / 1048576.0 / s.backup).toArray()),
                    group.stream().mapToLong(Sample::backupHeap).max().orElseThrow(), group.stream().mapToLong(Sample::restoreHeap).max().orElseThrow()));
        }
        Files.writeString(root.resolve("summary.csv"), csv);
    }
    private static void store(Properties properties, Path file) throws IOException {
        try (OutputStream output = Files.newOutputStream(file)) { properties.store(output, "PostgreSQL benchmark"); }
    }
    private static void tool(String name, String... args) throws Exception {
        String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        List<String> command = new ArrayList<>(); command.add(bin.resolve(name + suffix).toString()); command.addAll(List.of(args));
        run(new ProcessBuilder(command), root.resolve(name + "-" + System.nanoTime() + ".log"));
    }
    private static void run(ProcessBuilder pb, Path log) throws Exception {
        pb.environment().put("PGPASSWORD", "");
        await(pb.redirectErrorStream(true).redirectOutput(log.toFile()).start(), log);
    }
    private static void await(Process child, Path log) throws Exception {
        try {
            if (!child.waitFor(30, TimeUnit.MINUTES)) throw new IOException("Timed out; see " + log);
            if (child.exitValue() != 0) throw new IOException("Process failed (" + child.exitValue() + "); see " + log);
        } finally {
            if (child.isAlive()) {
                child.descendants().forEach(p -> p.destroyForcibly());
                child.destroyForcibly().waitFor();
            }
        }
    }
}
