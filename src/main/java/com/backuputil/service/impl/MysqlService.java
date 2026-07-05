package com.backuputil.service.impl;

import com.backuputil.config.DbConfig;
import com.backuputil.model.CompressionStrategy;
import com.backuputil.service.DatabaseService;
import com.backuputil.model.BackupResult;
import com.backuputil.model.RestoreResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class MysqlService implements DatabaseService {

    // Per-run cache: DB size captured during testConnection to avoid a second connection. null = unknown.
    private Long cachedSizeBytes = null;

    @Override
    public boolean testConnection (DbConfig config){
        String url = String.format("jdbc:mysql://%s:%d/%s",
                config.getHost(), config.getPort(), config.getDbName());

        System.out.println("Testing MySQL database handshake at: "+ url);

        try{
            Class.forName("com.mysql.cj.jdbc.Driver");

            try (Connection conn = DriverManager.getConnection(url, config.getUser(), config.getPassword())){
                if (conn != null && !conn.isClosed()){
                    System.out.println ("Handshake successful! My SQL credentials are valid.");
                    // Reuse this live connection to capture the DB size in the same round-trip.
                    cachedSizeBytes = querySizeBytes(conn, config.getDbName());
                    return true;
                }
            }

        }catch (ClassNotFoundException e){
            System.err.println ("MySQL Driver Missing: "+ e.getMessage());

        }catch (SQLException e){
            System.out.println ("MySQL Handshake Failed: "+ e.getMessage());
        }

        if (config.isMock()){
            System.out.println ("Warning: bypassing failure gate via active --mock flag...");
            return true;
        }
        return false;
    }

    @Override
    public long estimateSizeBytes(DbConfig config) {
        // Captured during testConnection in the normal flow — return it without reconnecting.
        if (cachedSizeBytes != null) return cachedSizeBytes;

        if (config == null || config.isMock()) return -1;

        // Fallback for callers that skipped testConnection. Short timeouts so a wrong host fails fast.
        String url = String.format("jdbc:mysql://%s:%d/%s?connectTimeout=5000&socketTimeout=10000",
                config.getHost(), config.getPort(), config.getDbName());

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            try (Connection conn = DriverManager.getConnection(url, config.getUser(), config.getPassword())) {
                cachedSizeBytes = querySizeBytes(conn, config.getDbName());
                return cachedSizeBytes;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    // Best-effort size query on an already-open connection. Returns -1 if it can't be read.
    private long querySizeBytes(Connection conn, String dbName) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(SUM(data_length + index_length), 0) "
                        + "FROM information_schema.tables WHERE table_schema = ?")) {
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
    public BackupResult backup (DbConfig config, String outputDir, CompressionStrategy strategy){
        // Defensive validation checks
        if (config == null){
            throw new IllegalArgumentException("Backup Core Error: Database configuration profile cannot be null.");

        } if (config.getPassword() == null){
            throw new IllegalArgumentException("Backup Core Error: Missing required parameter 'password'. ");
        }

        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        String finalFileName = String.format("%s_%s_backup.sql%s", config.getDbName(), timestamp, strategy.getExtension());
        java.nio.file.Path outputPath = java.nio.file.Paths.get(outputDir, finalFileName);

        System.out.println ("Initiating MYSQL streaming compression engine....");
        System.out.println("Target Destination Path: "+ outputPath.toAbsolutePath());

        // Building the next command-line arguments to run mysqldump externally
        // Note: No space between -p and the password string!


        String[] command = {
                "mysqldump",
                "-h", config.getHost(),
                "-P", String.valueOf(config.getPort()),
                "-u", config.getUser(),
                config.getDbName()
        };

        ProcessBuilder pb = new ProcessBuilder(command);
        // Pass the password via MYSQL_PWD env var (not a CLI arg) so it never appears in `ps aux`.
        pb.environment().put("MYSQL_PWD", config.getPassword());
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
            // Using a single - element array trick to bypass Java's lambda restriction,
            // which requires variables captured form the outer scope to be effectively final.
            String[] stderrOutput = {""};

            // Lightweight Virtual Thread to drain the stream concurrently.
            Thread stderrDrainer = Thread.ofVirtual().start(()->{
                try {
                    // readAllBytes() blocks until the stream ends. Running this on a separate thread
                    // keeps the OS stream buffer empty and prevents stalling.
                    stderrOutput[0] = new String(process.getErrorStream().readAllBytes());
                } catch (Exception ignored) {}
                    // Explicitly ignoring exceptions here as process termination or stream closure
                    // can throw expected IOExceptions during cleanup.
            });

            // Intercept standard output and pipe it through the GZIP compression layer
            try (java.io.InputStream processStdout = process.getInputStream();
                 java.io.FileOutputStream fileOutputStream = new java.io.FileOutputStream(outputPath.toFile());
                 java.io.OutputStream compressedOutputStream = buildCompressionStream(strategy, fileOutputStream)) {

                System.out.println("Pumping and compressing...");
                processStdout.transferTo(compressedOutputStream);
            }

            int exitCode = process.waitFor();
            stderrDrainer.join();
            long durationMs = System.currentTimeMillis() - startTime;

            if (exitCode == 0) {
                System.out.println("MySQL backup pipeline completed successfully!");
                return new BackupResult(
                        BackupResult.Status.SUCCESS,
                        config.getDbName(), "mysql",
                        outputPath.toAbsolutePath().toString(),
                        outputPath.toFile().length(),
                        durationMs, exitCode, null,
                        java.time.Instant.now()
                );
            } else {
                System.err.println("Native mysqldump failed with exit code (" + exitCode + "): " + stderrOutput[0]);
                discardFailedArtifact(outputPath);
                return new BackupResult(
                        BackupResult.Status.FAILED,
                        config.getDbName(), "mysql",
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
                    config.getDbName(), "mysql",
                    outputPath.toAbsolutePath().toString(),
                    0, durationMs, -1, e.getMessage(),
                    java.time.Instant.now()
            );
        }
    }

    @Override
    public RestoreResult restore(DbConfig config, String backupFilePath) {
        long startTime = System.currentTimeMillis();
        java.nio.file.Path source = java.nio.file.Paths.get(backupFilePath);

        if (config == null || config.getPassword() == null) {
            return new RestoreResult(RestoreResult.Status.ABORTED,
                    config == null ? "?" : config.getDbName(), "mysql", backupFilePath,
                    0, 0, -1, "Configuration or password missing", java.time.Instant.now());
        }
        if (!java.nio.file.Files.exists(source)) {
            return new RestoreResult(RestoreResult.Status.ABORTED,
                    config.getDbName(), "mysql", backupFilePath,
                    0, 0, -1, "Backup file not found: " + backupFilePath, java.time.Instant.now());
        }

        CompressionStrategy strategy = CompressionStrategy.fromFileName(source.getFileName().toString());
        System.out.println("Restoring mysql database '" + config.getDbName() + "' from " + source.toAbsolutePath());
        System.out.println("Decompressing via " + strategy.name() + " and streaming into mysql client...");

        // mysqldump produced a plain SQL script, so we replay it through the mysql client's stdin.
        String[] command = {
                "mysql",
                "-h", config.getHost(),
                "-P", String.valueOf(config.getPort()),
                "-u", config.getUser(),
                config.getDbName()
        };

        ProcessBuilder pb = new ProcessBuilder(command);
        // Password via MYSQL_PWD env var — never on the command line.
        pb.environment().put("MYSQL_PWD", config.getPassword());

        try {
            Process process = pb.start();

            String[] stderrOutput = {""};
            Thread stderrDrainer = Thread.ofVirtual().start(() -> {
                try {
                    stderrOutput[0] = new String(process.getErrorStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
            });
            Thread stdoutDrainer = Thread.ofVirtual().start(() -> {
                try {
                    process.getInputStream().readAllBytes();
                } catch (Exception ignored) {
                }
            });

            long bytesRestored;
            try (java.io.InputStream fileIn = java.nio.file.Files.newInputStream(source);
                 java.io.InputStream decompressed = com.backuputil.util.CompressionStreams.wrapDecompress(fileIn, strategy);
                 java.io.OutputStream toProcess = process.getOutputStream()) {

                System.out.println("Replaying SQL into the live database...");
                bytesRestored = decompressed.transferTo(toProcess);
            }

            int exitCode = process.waitFor();
            stderrDrainer.join();
            stdoutDrainer.join();
            long durationMs = System.currentTimeMillis() - startTime;

            if (exitCode == 0) {
                System.out.println("MySQL restore pipeline completed successfully!");
                return new RestoreResult(RestoreResult.Status.SUCCESS,
                        config.getDbName(), "mysql", source.toAbsolutePath().toString(),
                        bytesRestored, durationMs, exitCode, null, java.time.Instant.now());
            } else {
                System.err.println("Native mysql client failed with exit code (" + exitCode + "): " + stderrOutput[0]);
                return new RestoreResult(RestoreResult.Status.FAILED,
                        config.getDbName(), "mysql", source.toAbsolutePath().toString(),
                        bytesRestored, durationMs, exitCode, stderrOutput[0], java.time.Instant.now());
            }
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            System.err.println("Restore Engine Stream Failure: " + e.getMessage());
            return new RestoreResult(RestoreResult.Status.FAILED,
                    config.getDbName(), "mysql", source.toAbsolutePath().toString(),
                    0, durationMs, -1, e.getMessage(), java.time.Instant.now());
        }
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

