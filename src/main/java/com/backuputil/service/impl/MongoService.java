package com.backuputil.service.impl;

import com.backuputil.config.DbConfig;
import com.backuputil.service.DatabaseService;
import com.backuputil.model.BackupResult;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;

public class MongoService implements DatabaseService {

    @Override
    public boolean testConnection (DbConfig config){
        // constructing the mongodb connection
        String connectionString = String.format ("mongodb://%s:%s@%s:%d/%s?authSource=admin", config.getUser(), config.getPassword(), config.getHost(), config.getPort(), config.getDbName());
        System.out.println("Testing MongoDB database connection");

        // using the official mongoDB sync driver to validate the cluster link
        try (MongoClient mongoClient = MongoClients.create(connectionString)){
            // running a lightweight admin ping command to verify the active network link
            Document ping = mongoClient.getDatabase("admin").runCommand(new Document("ping", 1));
            if (ping.containsKey("ok") && ((Number) ping.get("ok")).doubleValue() == 1){
                System.out.println("Handshake successful! MongoDB cluster authentication is valid");
                return true;
            }
        }catch (Exception e){
            System.out.println("MongoDB Handshake failed: "+ e.getMessage());
        }
        // active mock check fallback for seamless local verification runs
        if (config.isMock()){
            System.out.println("Warning: bypassing failure gate via active --mock flag...");
            return true;
        }
        return false;
    }

    @Override
    public BackupResult backup (DbConfig config, String outputDir) {
        if (config == null) {
            throw new IllegalArgumentException("Backup Core Error: Database Configuration profile cannot be null");
        }
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        // MongoDB archives are binary dumps, so we use .bson.gz as our extension format
        String finalFileName = String.format("%s_%s_backup.bson.gz", config.getDbName(), timestamp);
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
                 java.util.zip.GZIPOutputStream gzipOutputStream = new java.util.zip.GZIPOutputStream(fileOutputStream)) {

                System.out.println("Pumping and compressing MongoDB binary streams concurrently...");
                processStdout.transferTo(gzipOutputStream);
            }

            int exitCode = process.waitFor();
            stderrDrainer.join();
            long durationMs = System.currentTimeMillis() - startTime;

            if (exitCode == 0) {
                System.out.println("MongoDB backup pipeline completed successfully!");
                return new BackupResult(
                        BackupResult.Status.SUCCESS,
                        config.getDbName(), "mysql",
                        outputPath.toAbsolutePath().toString(),
                        outputPath.toFile().length(),
                        durationMs, exitCode, null,
                        java.time.Instant.now()
                );
            } else {
                System.err.println("Native mongodump failed with exit code (" + exitCode + "): " + stderrOutput[0]);
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
            System.err.println("MongoDB Core Engine Stream Failure: " + e.getMessage());
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
    public void restore(DbConfig config, String backupFilePath) {
        System.out.println("MongoDB restore operation pending implementation...");
    }
    }

