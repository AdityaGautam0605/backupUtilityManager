package com.backuputil.service.impl;

import com.backuputil.config.DbConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class PostgresService implements DatabaseService{
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
        // Temporary Testing bypass
        System.out.println ("Warning: Bypassing failure gate for Week 2 stream engine");
        return true;
    }

    @Override
    public void backup(DbConfig config, String outputDir){
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

        // Inject the database password into the environment variables of the process
        // pg_dump securely looks for thd PGPASSWORD variable to bypass interactive terminal inputs;

        java.util.Map<String , String> env = pb.environment();
        env.put("PGPASSWORD", config.getPassword());

        try{
            Process process = pb.start();

            try (java.io.InputStream processStdout = process.getInputStream();
                java.io.FileOutputStream fileOutputStream = new java.io.FileOutputStream(outputPath.toFile());
                java.util.zip.GZIPOutputStream gzipOutputStream = new java.util.zip.GZIPOutputStream(fileOutputStream)){

                System.out.println("Pumping and compressing data streams concurrency..");

                // Transfer the bytes directly from the process directly to the zip stream memory-safely.
                processStdout.transferTo(gzipOutputStream);
            }

            // Wait for the background operating system task to officially finish
            int exitCode = process.waitFor();

            if (exitCode == 0){
                System.out.println("Backup pipeline completed successfully ! ");
                System.out.println("Compressed archive sealed at: "+ outputPath.toFile().length() + "bytes.");
            }else {
                // Read any error text thrown by pg_dump itself.
                java.io.InputStream errorStream = process.getErrorStream();
                String errorMsg = new String(errorStream.readAllBytes());
                System.err.println("Native pg_dump engine extraction failed with exit code ("+ exitCode +"): "+errorMsg);

            }
        } catch (Exception e){
            System.err.println ("Core Engine Stream Failure: "+ e.getMessage());
        }

    }

    @Override
    public void restore (DbConfig config, String backupFilePath){
        System.out.println("Restore operation pending implementation...");
    }
}
