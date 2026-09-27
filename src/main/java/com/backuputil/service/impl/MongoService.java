package com.backuputil.service.impl;

import com.backuputil.config.DbConfig;
import com.backuputil.model.CompressionStrategy;
import com.backuputil.service.DatabaseService;
import com.backuputil.model.BackupResult;
import com.backuputil.model.RestoreResult;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;

public class MongoService implements DatabaseService {

    // Per-run cache: DB size captured during testConnection to avoid a second connection. null = unknown.
    private Long cachedSizeBytes = null;

    @Override
    public boolean testConnection(DbConfig config) {
        // constructing the mongodb connection. User/password are URL-encoded because a
        // password containing @ : / ? # would otherwise corrupt the URI and misparse the host.
        String connectionString = String.format("mongodb://%s:%s@%s:%d/%s?authSource=admin",
                enc(config.getUser()), enc(config.getPassword()), config.getHost(), config.getPort(), config.getDbName());
        System.out.println("Testing MongoDB database connection");

        // using the official mongoDB sync driver to validate the cluster link
        try (MongoClient mongoClient = MongoClients.create(connectionString)) {
            // running a lightweight admin ping command to verify the active network link
            Document ping = mongoClient.getDatabase("admin").runCommand(new Document("ping", 1));
            if (ping.containsKey("ok") && ((Number) ping.get("ok")).doubleValue() == 1) {
                System.out.println("Handshake successful! MongoDB cluster authentication is valid");
                // Reuse this live client to capture the DB size in the same session.
                cachedSizeBytes = querySizeBytes(mongoClient, config.getDbName());
                return true;
            }
        } catch (Exception e) {
            System.out.println("MongoDB Handshake failed: " + e.getMessage());
        }
        // active mock check fallback for seamless local verification runs
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

        if (config == null || config.isMock()) return -1;

        // Fallback for callers that skipped testConnection.
        String connectionString = String.format(
                "mongodb://%s:%s@%s:%d/%s?authSource=admin&serverSelectionTimeoutMS=5000",
                enc(config.getUser()), enc(config.getPassword()), config.getHost(), config.getPort(), config.getDbName());

        try (MongoClient mongoClient = MongoClients.create(connectionString)) {
            cachedSizeBytes = querySizeBytes(mongoClient, config.getDbName());
            return cachedSizeBytes;
        } catch (Exception e) {
            return -1;
        }
    }

    // Best-effort dbStats query on an already-open client. Returns -1 if it can't be read.
    private long querySizeBytes(MongoClient mongoClient, String dbName) {
        try {
            Document stats = mongoClient.getDatabase(dbName).runCommand(new Document("dbStats", 1));
            // dataSize is the uncompressed size of the documents — the best predictor of dump size.
            Object dataSize = stats.get("dataSize");
            if (dataSize instanceof Number number) {
                return number.longValue();
            }
        } catch (Exception e) {
            // ignore — size is advisory only
        }
        return -1;
    }

    @Override
    public BackupResult backup(DbConfig config, String outputDir, CompressionStrategy strategy) {
        if (config == null) {
            throw new IllegalArgumentException("Backup Core Error: Database Configuration profile cannot be null");
        }
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        // MongoDB archives are binary dumps, so we use .bson.gz as our extension format
        String finalFileName = String.format("%s_%s_backup.bson%s", config.getDbName(), timestamp, strategy.getExtension());
        java.nio.file.Path outputPath = java.nio.file.Paths.get(outputDir, finalFileName);

        System.out.println("Initiating MongoDB streaming compression engine...");
        System.out.println("Target destination path: " + outputPath.toAbsolutePath());

        // Build the native arguments to call mongodump
        // CRITICAL: --archive with no file path forces output directly to standard stdout stream!
        String[] command = {
                "mongodump",
                "--host", config.getHost(),
                "--port", String.valueOf(config.getPort()),
                "--username", config.getUser(),
                "--password", config.getPassword(),
                "--db", config.getDbName(),
                "--archive"
        };

        ProcessBuilder pb = new ProcessBuilder(command);
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

            String[] stderrOutput = {""};

            Thread stderrDrainer = Thread.ofVirtual().start(() -> {
                try {
                    stderrOutput[0] = new String(process.getErrorStream().readAllBytes());
                } catch (Exception ignored) {
                }
                // Explicitly ignoring exceptions here as process termination or stream closure
                // can throw expected IOExceptions during cleanup.
            });

            // Stream and compress the binary BSON bytes into our local archive file
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
                System.out.println("MongoDB backup pipeline completed successfully!");
                return new BackupResult(
                        BackupResult.Status.SUCCESS,
                        config.getDbName(), "mongo",
                        outputPath.toAbsolutePath().toString(),
                        outputPath.toFile().length(),
                        durationMs, exitCode, null,
                        java.time.Instant.now()
                );
            } else {
                System.err.println("Native mongodump failed with exit code (" + exitCode + "): " + stderrOutput[0]);
                discardFailedArtifact(outputPath);
                return new BackupResult(
                        BackupResult.Status.FAILED,
                        config.getDbName(), "mongo",
                        outputPath.toAbsolutePath().toString(),
                        0, durationMs, exitCode, stderrOutput[0],
                        java.time.Instant.now()
                );
            }
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            System.err.println("MongoDB Core Engine Stream Failure: " + e.getMessage());
            discardFailedArtifact(outputPath);
            return new BackupResult(
                    BackupResult.Status.FAILED,
                    config.getDbName(), "mongo",
                    outputPath.toAbsolutePath().toString(),
                    0, durationMs, -1, e.getMessage(),
                    java.time.Instant.now()
            );
        }
    }

    @Override
    public RestoreResult restore(DbConfig config, String backupFilePath) {
        return com.backuputil.service.NativeRestore.restore(config, backupFilePath, "mongo");
    }

    // URL-encode a userinfo component so special characters survive inside the mongodb:// URI.
    private static String enc(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, java.nio.charset.StandardCharsets.UTF_8);
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

