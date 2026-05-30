package com.backuputil.service.impl;

import com.backuputil.config.DbConfig;
import com.backuputil.service.impl.DatabaseService;
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
                System.out.println("Handshake successful! MongoDB cluster authenticaton is valid");
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
    public void backup (DbConfig config, String outputDir){
        if (config == null){
            throw new IllegalArgumentException("Backup Core Error: Database Configuration profile cannot be null");
        }
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        // MongoDB archives are binary dumps, so we use .bson.gz as our extension format
        String finalFileName = String.format("%s_%s_backup.bson.gz", config.getDbName(), timestamp);
        java.nio.file.Path outputPath = java.nio.file.Paths.get(outputDir, finalFileName);

        System.out.println("📦 Initiating MongoDB streaming compression engine...");
        System.out.println("💾 Target destination path: " + outputPath.toAbsolutePath());

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

        try {
            Process process = pb.start();

            // Stream and compress the binary BSON bytes into our local archive file
            try (java.io.InputStream processStdout = process.getInputStream();
                 java.io.FileOutputStream fileOutputStream = new java.io.FileOutputStream(outputPath.toFile());
                 java.util.zip.GZIPOutputStream gzipOutputStream = new java.util.zip.GZIPOutputStream(fileOutputStream)) {

                System.out.println("⚡ Pumping and compressing MongoDB binary streams concurrently...");
                processStdout.transferTo(gzipOutputStream);
            }

            int exitCode = process.waitFor();

            if (exitCode == 0) {
                System.out.println("🎉 MongoDB backup pipeline completed successfully!");
                System.out.println("📐 Compressed archive sealed at: " + outputPath.toFile().length() + " bytes.");
            } else {
                java.io.InputStream errorStream = process.getErrorStream();
                String errorMsg = new String(errorStream.readAllBytes());
                System.err.println("❌ Native mongodump engine extraction failed with exit code (" + exitCode + "): " + errorMsg);
            }

        } catch (Exception e) {
            System.err.println("❌ MongoDB Core Engine Stream Failure: " + e.getMessage());
        }
    }

    @Override
    public void restore(DbConfig config, String backupFilePath) {
        System.out.println("MongoDB restore operation pending implementation...");
    }
    }

