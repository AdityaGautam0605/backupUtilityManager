package com.backuputil.service.impl;

import com.backuputil.config.DbConfig;
import com.backuputil.service.DatabaseService;
import com.backuputil.model.BackupResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class MysqlService implements DatabaseService {

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
    public BackupResult backup (DbConfig config, String outputDir){
        // Defensive validation checks
        if (config == null){
            throw new IllegalArgumentException("Backup Core Error: Database configuration profile cannot be null.");

        } if (config.getPassword() == null){
            throw new IllegalArgumentException("Backup Core Error: Missing required parameter 'password'. ");
        }

        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        String finalFileName = String.format("%s_%s_backup.sql.gz", config.getDbName(), timestamp);
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
                 java.util.zip.GZIPOutputStream gzipOutputStream = new java.util.zip.GZIPOutputStream(fileOutputStream)) {

                System.out.println("Pumping and compressing MySQL streams concurrently...");
                processStdout.transferTo(gzipOutputStream);
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
        System.out.println("MySQL restore operation pending implementation...");
    }
}

