package com.backuputil.service.impl;

import com.backuputil.config.DbConfig;
import com.backuputil.model.BackupResult;
import com.backuputil.model.CompressionStrategy;
import com.backuputil.model.RestoreResult;
import com.backuputil.service.DatabaseService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class PostgresService implements DatabaseService {

    // Per-run cache: the DB size captured during testConnection so the compression
    // advisor doesn't have to open a second connection moments later. null = not yet known.
    private Long cachedSizeBytes = null;

    @Override
    public boolean testConnection(DbConfig config) {
        String url = String.format("jdbc:postgresql://%s:%d/%s",
                config.getHost(), config.getPort(), config.getDbName());

        System.out.println("Testing database handshake at: " + url);

        try (Connection conn = DriverManager.getConnection(url, config.getUser(), config.getPassword())) {
            if (conn != null && !conn.isClosed()) {
                System.out.println("Handshake successful! Database Credentials are valid. ");
                // Reuse this live connection to capture the DB size in the same round-trip.
                cachedSizeBytes = querySizeBytes(conn, config.getDbName());
                return true;
            }
        } catch (SQLException e) {
            System.out.println("Database Handshake Failed: " + e.getMessage());
        }

        if (config.isMock()) {
            System.out.println("Warning: bypassing failure gate via active --mock flag...");
            return true;
        }
        return false;
    }

    @Override
    public long estimateSizeBytes(DbConfig config) {
        // Captured during testConnection in the normal flow — return it without reconnecting.
        if (cachedSizeBytes != null) return cachedSizeBytes;

        // No live database to measure in mock mode.
        if (config == null || config.isMock()) return -1;

        // Fallback for callers that skipped testConnection. Short timeouts so a wrong host fails fast.
        String url = String.format("jdbc:postgresql://%s:%d/%s?connectTimeout=5&socketTimeout=10",
                config.getHost(), config.getPort(), config.getDbName());

        try (Connection conn = DriverManager.getConnection(url, config.getUser(), config.getPassword())) {
            cachedSizeBytes = querySizeBytes(conn, config.getDbName());
            return cachedSizeBytes;
        } catch (Exception e) {
            return -1;
        }
    }

    // Best-effort size query on an already-open connection. Returns -1 if it can't be read.
    private long querySizeBytes(Connection conn, String dbName) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_database_size(?)")) {
            ps.setString(1, dbName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (Exception e) {
            // ignore — size is advisory only
        }
        return -1;
    }

    @Override
    public BackupResult backup(DbConfig config, String outputDir, CompressionStrategy strategy) {
        // Defensive Validation Check
        if (config == null) {
            throw new IllegalArgumentException("Backup Core Error: Database Configuration profile cannot be null. ");
        }
        if (config.getPassword() == null) {
            throw new IllegalArgumentException("Backup Core Error: Missing required parameter 'password. Operating system variables cannot accept null values.");

        }
        if (config.getDbName() == null || config.getDbName().trim().isEmpty()) {
            throw new IllegalArgumentException("Backup Core Error: Target database name parameter cannot be empty. ");

        }
        // filename and directory setup
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        String finalFileName = String.format("%s_%s_backup.sql%s", config.getDbName(), timestamp, strategy.getExtension());
        java.nio.file.Path outputPath = java.nio.file.Paths.get(outputDir, finalFileName);

        System.out.println("Initiating streaming compression engine...");
        System.out.println("Target destination path: " + outputPath.toAbsolutePath());

        // Native Process args setup
        String[] command = {
                "pg_dump",
                "-h", config.getHost(),
                "-p", String.valueOf(config.getPort()),
                "-U", config.getUser(),
                "-Fp", // Plain SQL script output stream
                config.getDbName()
        };

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("PGPASSWORD", config.getPassword());

        // track start time
        long startTime = System.currentTimeMillis();

        // Explanation to the below code:
        // Why?
        // When executing an external process, the OS allocates a small, fixed size buffer for the process's standard err
        // (stderr) and standard output (stdout) streams. If the external process generates a lot of error output and fills this
        // buffer, it will block and hang indefinitely waiting for the buffer to be cleared.
        //
        // How it works?
        // By reading the error stream asynchronously on a background Virtual Thread, we continuously drain the OS buffer as fast as it fills.
        // This ensures the external process never stalls, while allowing our main thread to continue executing or safely wait for the process
        // to exit via proces.waitFor().

        try {
            Process process = pb.start();

            // capturing the stderr output reference (array trick for lambda capture)
            String[] stderrOutput = {""};

            // Drain stderr on a background thread to prevent deadlock
            Thread stderrDrainer = Thread.ofVirtual().start(() -> {
                try {
                    stderrOutput[0] = new String(process.getErrorStream().readAllBytes());
                } catch (Exception ignored) {
                }
                // Explicitly ignoring exceptions here as process termination or stream closure
                // can throw expected IOExceptions during cleanup.
            });

            try (java.io.InputStream processStdout = process.getInputStream();
                 java.io.FileOutputStream fileOutputStream = new java.io.FileOutputStream(outputPath.toFile());
                 java.io.OutputStream compressedOutputStream = buildCompressionStream(strategy, fileOutputStream)) {

                System.out.println("Pumping and compressing data streams concurrency..");

                // Transfer the bytes directly from the process directly to the zip stream memory-safely.
                System.out.println("Pumping and compressing...");
                processStdout.transferTo(compressedOutputStream);
            }


            // Wait for the background operating system task to officially finish
            int exitCode = process.waitFor();
            stderrDrainer.join();// wait for stderr thread to finish
            long durationMs = System.currentTimeMillis() - startTime;

            if (exitCode == 0) {
                System.out.println("Backup pipeline completed successfully ! ");
                return new BackupResult(
                        BackupResult.Status.SUCCESS,
                        config.getDbName(), "postgres",
                        outputPath.toAbsolutePath().toString(),
                        outputPath.toFile().length(),
                        durationMs, exitCode, null,
                        java.time.Instant.now()
                );
            } else {
                // Read any error text thrown by pg_dump itself.
                System.err.println("Native pg_dump failed with exit code (" + exitCode + "): " + stderrOutput[0]);
                discardFailedArtifact(outputPath);
                return new BackupResult(
                        BackupResult.Status.FAILED,
                        config.getDbName(), "postgres",
                        outputPath.toAbsolutePath().toString(),
                        0, durationMs, exitCode, stderrOutput[0],
                        java.time.Instant.now()
                );
            }
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            System.err.println("Core Engine Stream Failure: " + e.getMessage());
            discardFailedArtifact(outputPath);
            return new BackupResult(
                    BackupResult.Status.FAILED,
                    config.getDbName(), "postgres",
                    outputPath.toAbsolutePath().toString(),
                    0, durationMs, -1, e.getMessage(),
                    java.time.Instant.now()
            );
        }

    }

    @Override
    public RestoreResult restore(DbConfig config, String backupFilePath) {
        return com.backuputil.service.NativeRestore.restore(config, backupFilePath, "postgres");
    }

    // A failed backup must not leave a partial/empty archive on disk — it would pollute the
    // trends report and the restore picker, and could be mistaken for a good backup.
    private void discardFailedArtifact(java.nio.file.Path outputPath) {
        try {
            if (java.nio.file.Files.deleteIfExists(outputPath)) {
                System.err.println("Cleaned up incomplete archive: " + outputPath.toAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("Note: could not remove incomplete archive "
                    + outputPath.toAbsolutePath() + ": " + e.getMessage());
        }
    }

    private java.io.OutputStream buildCompressionStream(
            com.backuputil.model.CompressionStrategy strategy,
            java.io.FileOutputStream fileOutputStream) throws Exception {
        return switch (strategy) {
            case GZIP -> new java.util.zip.GZIPOutputStream(fileOutputStream);
            case BZIP2 -> {
                try {
                    Class<?> bzip2Class = Class.forName(
                            "org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream");
                    yield (java.io.OutputStream) bzip2Class
                            .getConstructor(java.io.OutputStream.class)
                            .newInstance(fileOutputStream);
                } catch (ClassNotFoundException e) {
                    System.out.println("[Compression] BZIP2 library not found — falling back to GZIP");
                    yield new java.util.zip.GZIPOutputStream(fileOutputStream);
                }
            }
            case LZ4 -> {
                try {
                    Class<?> lz4Class = Class.forName(
                            "net.jpountz.lz4.LZ4FrameOutputStream");
                    yield (java.io.OutputStream) lz4Class
                            .getConstructor(java.io.OutputStream.class)
                            .newInstance(fileOutputStream);
                } catch (ClassNotFoundException e) {
                    System.out.println("[Compression] LZ4 library not found — falling back to GZIP");
                    yield new java.util.zip.GZIPOutputStream(fileOutputStream);
                }
            }
            case ZSTD -> {
                try {
                    Class<?> zstdClass = Class.forName(
                            "com.github.luben.zstd.ZstdOutputStream");
                    yield (java.io.OutputStream) zstdClass
                            .getConstructor(java.io.OutputStream.class)
                            .newInstance(fileOutputStream);
                } catch (ClassNotFoundException e) {
                    System.out.println("[Compression] ZSTD library not found — falling back to GZIP");
                    yield new java.util.zip.GZIPOutputStream(fileOutputStream);
                }
            }
        };
    }
}

