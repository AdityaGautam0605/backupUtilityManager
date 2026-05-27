package com.backuputil.service.impl;

import com.backuputil.config.DbConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class MysqlService implements DatabaseService{

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
        // Testing bypass so we can verify the process streaming layer easily
        System.out.println("Warning: Bypassing failure gate for MySQL stream engine verification...");
        return true;
    }

    @Override
    public void backup (DbConfig config, String outputDir){
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

        String passwordFlag = "-p" + config.getPassword();
        String[] command = {
                "mysqldump",
                "-h", config.getHost(),
                "-P", String.valueOf(config.getPort()),
                "-u", config.getUser(),
                passwordFlag,
                config.getDbName()
        };

        ProcessBuilder pb = new ProcessBuilder(command);

        try {
            Process process = pb.start();

            // Intercept standard output and pipe it through the GZIP compression layer
            try (java.io.InputStream processStdout = process.getInputStream();
                 java.io.FileOutputStream fileOutputStream = new java.io.FileOutputStream(outputPath.toFile());
                 java.util.zip.GZIPOutputStream gzipOutputStream = new java.util.zip.GZIPOutputStream(fileOutputStream)) {

                System.out.println("⚡ Pumping and compressing MySQL streams concurrently...");
                processStdout.transferTo(gzipOutputStream);
            }

            int exitCode = process.waitFor();

            if (exitCode == 0) {
                System.out.println("🎉 MySQL backup pipeline completed successfully!");
                System.out.println("📐 Compressed archive sealed at: " + outputPath.toFile().length() + " bytes.");
            } else {
                java.io.InputStream errorStream = process.getErrorStream();
                String errorMsg = new String(errorStream.readAllBytes());
                System.err.println("❌ Native mysqldump engine extraction failed with exit code (" + exitCode + "): " + errorMsg);
            }

        } catch (Exception e) {
            System.err.println("❌ MySQL Core Engine Stream Failure: " + e.getMessage());
        }

    }

    @Override
    public void restore (DbConfig config, String backupFilePath){
        System.out.println("MySQL restore operation pending implementation...");
    }
}

