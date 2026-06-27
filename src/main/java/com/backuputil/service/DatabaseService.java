package com.backuputil.service;
import com.backuputil.config.DbConfig;
import com.backuputil.model.BackupResult;
import com.backuputil.model.CompressionStrategy;
import com.backuputil.model.RestoreResult;

public interface DatabaseService{
        boolean testConnection (DbConfig config);
        BackupResult backup(DbConfig config, String outputDir, CompressionStrategy strategy) throws Exception;
        RestoreResult restore(DbConfig config, String backupFilePath);

        // Estimated uncompressed size of the database in bytes, used by the compression
        // advisor to pick a strategy. Returns a negative value when the size cannot be
        // determined (e.g. mock mode or a query failure) so callers can fall back.
        long estimateSizeBytes(DbConfig config);
    }
