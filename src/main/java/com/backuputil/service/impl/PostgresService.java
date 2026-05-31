package com.backuputil.service.impl;

import com.backuputil.config.DbConfig;
import com.backuputil.model.BackupResult;
import com.backuputil.service.DatabaseService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class PostgresService implements DatabaseService {
    @Override
    public boolean testConnection (DbConfig config){
        String url = String.format("jdbc:postgresql://%s:%d/%s",
                config.getHost(), config.getPort(), config.getDbName());

        System.out.println("Testing database handshake at: " + url);

        try (Connection conn = DriverManager.getConnection(url, config.getUser(), config.getPassword())){
            if (conn != null && !conn.isClosed()){
                System.out.println("Handshake successful! Database Credentials are valid. ");
                return true;
            }
        }catch (SQLException e){
            System.out.println("Database Handshake Failed: "+ e.getMessage());
        }

        if (config.isMock()){
            System.out.println("Warning: bypassing failure gate via active --mock flag...");
            return true;
        }
        return false;
    }

    @Override
    public BackupResult backup(DbConfig config, String outputDir) throws Exception{
        // Defensive Validation Check
        if (config == null){
            throw new IllegalArgumentException("Backup Core Error: Database Configuration profile cannot be null. ");
        }
        if (config.getPassword() == null){
            throw new IllegalArgumentException("Backup Core Error: Missing required parameter 'password. Operating system variables cannot accept null values.");

        }if (config.getDbName()== null || config.getDbName().trim().isEmpty()){
            throw new IllegalArgumentException("Backup Core Error: Target database name parameter cannot be empty. ");

        }
        // filename and directory setup
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format (new java.util.Date());
        String finalFileName = String.format("%s_%s_backup.sql.gz", config.getDbName(), timestamp);
        java.nio.file.Path outputPath = java.nio.file.Paths.get(outputDir, finalFileName);

        System.out.println("Initiating streaming compression engine...");
        System.out.println("Target destination path: "+ outputPath.toAbsolutePath());

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

        try{
            Process process = pb.start();

            // capturing the stderr output reference (array trick for lambda capture)
            String[] stderrOutput = {""};

            // Drain stderr on a background thread to prevent deadlock
            Thread stderrDrainer = Thread.ofVirtual().start(()->{
                try{
                    stderrOutput[0] = new String(process.getErrorStream().readAllBytes());
                }catch (Exception ignored){}
                // Explicitly ignoring exceptions here as process termination or stream closure
                // can throw expected IOExceptions during cleanup.
            });

            try (java.io.InputStream processStdout = process.getInputStream();
                java.io.FileOutputStream fileOutputStream = new java.io.FileOutputStream(outputPath.toFile());
                java.util.zip.GZIPOutputStream gzipOutputStream = new java.util.zip.GZIPOutputStream(fileOutputStream)){

                System.out.println("Pumping and compressing data streams concurrency..");

                // Transfer the bytes directly from the process directly to the zip stream memory-safely.
                processStdout.transferTo(gzipOutputStream);
            }


            // Wait for the background operating system task to officially finish
            int exitCode = process.waitFor();
            stderrDrainer.join();// wait for stderr thread to finish
            long durationMs = System.currentTimeMillis() - startTime;

            if (exitCode == 0){
                System.out.println("Backup pipeline completed successfully ! ");
                return new BackupResult(
                        BackupResult.Status.SUCCESS,
                        config.getDbName(), "postgres",
                        outputPath.toAbsolutePath().toString(),
                        outputPath.toFile().length(),
                        durationMs, exitCode, null,
                        java.time.Instant.now()
                );
            }else {
                // Read any error text thrown by pg_dump itself.
                System.err.println("Native pg_dump failed with exit code (" + exitCode + "): " + stderrOutput[0]);
                return new BackupResult(
                        BackupResult.Status.FAILED,
                        config.getDbName(), "postgres",
                        outputPath.toAbsolutePath().toString(),
                        0, durationMs, exitCode, stderrOutput[0],
                        java.time.Instant.now()
                );
            }
        } catch (Exception e){
            long durationMs = System.currentTimeMillis() - startTime;
            System.err.println("Core Engine Stream Failure: " + e.getMessage());
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
    public void restore (DbConfig config, String backupFilePath){
        System.out.println("Restore operation pending implementation...");
    }
}
