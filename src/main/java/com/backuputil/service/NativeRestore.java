package com.backuputil.service;

import com.backuputil.config.DbConfig;
import com.backuputil.model.CompressionStrategy;
import com.backuputil.model.RestoreResult;
import com.backuputil.util.CompressionStreams;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared restore lifecycle: validate the complete compressed input before any database writes. */
public final class NativeRestore {
    private static final int MAX_DIAGNOSTICS = 64 * 1024;
    private static final Pattern GENERATED_ARCHIVE =
            Pattern.compile("^(.*)_\\d{8}_\\d{6}_backup\\.(sql|bson)\\.(gz|bz2|lz4|zst)$");

    private NativeRestore() {}

    @FunctionalInterface
    interface ProcessStarter {
        Process start(ProcessBuilder builder) throws IOException;
    }

    public static RestoreResult restore(DbConfig config, String file, String engine) {
        return restore(config, file, engine, ProcessBuilder::start);
    }

    static RestoreResult restore(DbConfig config, String file, String engine, ProcessStarter starter) {
        long started = System.nanoTime();
        Process process = null;
        Thread drainer = null;
        Thread feeder = null;
        String[] diagnostics = {""};
        Exception[] drainError = {null};
        Exception[] feedError = {null};
        boolean launched = false;
        try {
            validateConfig(config, engine);
            if (file == null || file.isBlank()) throw new IOException("Backup file is required");
            Path source = Path.of(file);
            if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
                throw new IOException("Backup must be a readable regular file: " + file);
            }
            validateArchiveName(source.getFileName().toString(), config.getDbName(), engine);
            CompressionStrategy strategy = CompressionStrategy.fromFileName(source.getFileName().toString());

            // Keep the same handle and shared lock for both passes. No decompressed file is written.
            // Locks are advisory on some filesystems: callers must keep the source immutable.
            try (var channel = java.nio.channels.FileChannel.open(source, java.nio.file.StandardOpenOption.READ);
                 var lock = channel.lock(0, Long.MAX_VALUE, true)) {
                long sourceSize = channel.size();
                var modified = Files.getLastModifiedTime(source);
                var validatedDigest = java.security.MessageDigest.getInstance("SHA-256");
                long bytes;
                try (InputStream input = decompress(channel, strategy)) {
                    bytes = copy(input, java.io.OutputStream.nullOutputStream(), validatedDigest);
                }
                if (bytes == 0) throw new IOException("Backup contains no decompressed data");
                byte[] expectedDigest = validatedDigest.digest();
                checkSource(source, channel, sourceSize, modified);
                channel.position(0);

                ProcessBuilder builder = command(config, engine);
                builder.redirectErrorStream(true);
                process = starter.start(builder);
                launched = true;
                Process child = process;
                // A concurrent feeder is essential: the client may fill its output pipe while
                // consuming input. The main thread remains interruptible in waitFor().
                drainer = Thread.ofVirtual().start(() -> {
                    try (InputStream output = child.getInputStream()) {
                        diagnostics[0] = readDiagnostics(output);
                    } catch (IOException e) {
                        drainError[0] = e;
                        stopAndReap(child);
                    }
                });
                feeder = Thread.ofVirtual().start(() -> {
                    java.io.OutputStream toClient = child.getOutputStream();
                    try (InputStream input = decompress(channel, strategy)) {
                        checkSource(source, channel, sourceSize, modified);
                        var replayDigest = java.security.MessageDigest.getInstance("SHA-256");
                        long delivered = copy(input, toClient, replayDigest);
                        checkSource(source, channel, sourceSize, modified);
                        if (delivered != bytes || !java.security.MessageDigest.isEqual(expectedDigest, replayDigest.digest())) {
                            throw new IOException("Backup changed between validation and restore");
                        }
                        toClient.flush();
                    } catch (Exception e) {
                        feedError[0] = e;
                        // A broken pipe often means the client is already exiting with a useful
                        // SQL error code. Give it a short chance to exit before killing it.
                        try { child.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                        // Kill BEFORE closing stdin: EOF could otherwise commit a partial psql script.
                        stopAndReap(child);
                    } finally {
                        try { toClient.close(); }
                        catch (IOException e) {
                            if (feedError[0] == null) feedError[0] = e;
                            stopAndReap(child);
                        }
                    }
                });
                try {
                    int exitCode = process.waitFor();
                    feeder.join();
                    drainer.join();
                    if (exitCode != 0) {
                        return result(config, file, engine, started, RestoreResult.Status.FAILED, 0,
                                exitCode, "Restore client exited with code " + exitCode + ": " + diagnostics[0]
                                + (feedError[0] == null ? "" : "\nInput stream failed: " + feedError[0].getMessage()));
                    }
                    if (feedError[0] != null) throw feedError[0];
                    if (drainError[0] != null) throw drainError[0];
                    return result(config, file, engine, started, RestoreResult.Status.SUCCESS, bytes, 0, null);
                } finally {
                    // Stop and join workers before releasing the shared archive handle.
                    stopAndReap(child);
                    joinWorker(feeder);
                    joinWorker(drainer);
                }
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return result(config, file, engine, started,
                    launched ? RestoreResult.Status.FAILED : RestoreResult.Status.ABORTED,
                    0, -1, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (process != null) {
                stopAndReap(process);
                try { process.getInputStream().close(); } catch (IOException ignored) {}
                try { process.getErrorStream().close(); } catch (IOException ignored) {}
                try { process.getOutputStream().close(); } catch (IOException ignored) {}
            }
        }
    }

    private static InputStream decompress(java.nio.channels.FileChannel channel, CompressionStrategy strategy) throws Exception {
        // Closing each decoder must release its buffers without closing the shared channel.
        InputStream raw = new java.io.FilterInputStream(java.nio.channels.Channels.newInputStream(channel)) {
            @Override public void close() {}
        };
        return CompressionStreams.wrapDecompress(raw, strategy);
    }

    private static long copy(InputStream input, java.io.OutputStream output,
                             java.security.MessageDigest digest) throws IOException, InterruptedException {
        byte[] buffer = new byte[8192];
        long bytes = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Restore cancelled");
            digest.update(buffer, 0, count);
            output.write(buffer, 0, count);
            bytes += count;
        }
        return bytes;
    }

    private static void checkSource(Path path, java.nio.channels.FileChannel channel, long size,
                                    java.nio.file.attribute.FileTime modified) throws IOException {
        if (channel.size() != size || !Files.getLastModifiedTime(path).equals(modified)) {
            throw new IOException("Backup changed between validation and restore");
        }
    }

    private static void stopAndReap(Process child) {
        if (!child.isAlive()) return;
        child.destroyForcibly();
        boolean interrupted = Thread.interrupted();
        try {
            while (true) {
                try { child.waitFor(); break; }
                catch (InterruptedException e) { interrupted = true; }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void joinWorker(Thread worker) {
        boolean interrupted = Thread.interrupted();
        try {
            while (worker.isAlive()) {
                try { worker.join(); }
                catch (InterruptedException e) { interrupted = true; }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void validateConfig(DbConfig config, String engine) throws IOException {
        if (!List.of("postgres", "mysql", "mongo").contains(engine)) {
            throw new IOException("Unsupported restore engine: " + engine);
        }
        if (config == null || config.getPassword() == null || config.getDbName() == null
                || config.getDbName().isBlank() || config.getHost() == null || config.getHost().isBlank()
                || config.getUser() == null || config.getUser().isBlank()
                || config.getPort() < 1 || config.getPort() > 65535) {
            throw new IOException("Restore requires valid host, port, user, password and database settings");
        }
        // psql treats a dbname containing '=' or a URI as a connection string, which could
        // override the host/database shown in the confirmation. Accept literal database names only.
        if (engine.equals("postgres") && (config.getDbName().contains("=")
                || config.getDbName().startsWith("postgresql://")
                || config.getDbName().startsWith("postgres://"))) {
            throw new IOException("Restore requires a literal database name, not a connection string");
        }
        // The name becomes a namespace pattern. Reject metacharacters rather than broadening it.
        if (engine.equals("mongo") && !config.getDbName().matches("[A-Za-z0-9_-]+")) {
            throw new IOException("MongoDB restore database names must contain only letters, digits, '_' or '-'");
        }
    }

    static void validateArchiveName(String name, String dbName, String engine) throws IOException {
        Matcher generated = GENERATED_ARCHIVE.matcher(name);
        if (generated.matches()) {
            boolean bson = generated.group(2).equals("bson");
            if (bson != engine.equals("mongo")) {
                throw new IOException("Archive format does not match the selected database engine");
            }
            if (engine.equals("mongo") && !generated.group(1).equals(dbName)) {
                throw new IOException("MongoDB restores require the original database name; namespace renaming is not supported");
            }
        }
    }

    private static ProcessBuilder command(DbConfig config, String engine) {
        List<String> args = new ArrayList<>();
        switch (engine) {
            case "postgres" -> args.addAll(List.of("psql", "-X", "--no-password",
                    "--set=ON_ERROR_STOP=on", "--single-transaction", "--file=-",
                    "-h", config.getHost(), "-p", Integer.toString(config.getPort()),
                    "-U", config.getUser(), "-d", config.getDbName()));
            case "mysql" -> args.addAll(List.of("mysql", "--no-defaults", "--batch",
                    "--host=" + config.getHost(), "--port=" + config.getPort(),
                    "--user=" + config.getUser(), "--database=" + config.getDbName()));
            case "mongo" -> args.addAll(List.of("mongorestore", "--host", config.getHost(),
                    "--port", Integer.toString(config.getPort()), "--username", config.getUser(),
                    "--password", config.getPassword(), "--authenticationDatabase", "admin",
                    "--nsInclude=" + config.getDbName() + ".*", "--stopOnError", "--archive"));
            default -> throw new IllegalArgumentException("Unsupported engine: " + engine);
        }
        ProcessBuilder builder = new ProcessBuilder(args);
        if (engine.equals("postgres")) builder.environment().put("PGPASSWORD", config.getPassword());
        if (engine.equals("mysql")) builder.environment().put("MYSQL_PWD", config.getPassword());
        return builder;
    }

    private static String readDiagnostics(InputStream input) throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        boolean truncated = false;
        while ((count = input.read(buffer)) != -1) {
            int keep = Math.min(count, MAX_DIAGNOSTICS - captured.size());
            captured.write(buffer, 0, keep);
            truncated |= keep < count;
        }
        return captured.toString(StandardCharsets.UTF_8) + (truncated ? "\n[output truncated]" : "");
    }

    private static RestoreResult result(DbConfig config, String file, String engine, long started,
                                        RestoreResult.Status status, long bytes, int code, String error) {
        return new RestoreResult(status, config == null ? "?" : config.getDbName(), engine, file,
                bytes, (System.nanoTime() - started) / 1_000_000, code, error, Instant.now());
    }
}
