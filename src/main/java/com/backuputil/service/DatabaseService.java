package com.backuputil.service;
import com.backuputil.config.DbConfig;
import com.backuputil.model.BackupResult;
import com.backuputil.model.CompressionStrategy;

public interface DatabaseService{
        boolean testConnection (DbConfig config);
        BackupResult backup(DbConfig config, String outputDir, CompressionStrategy strategy) throws Exception;
        void restore(DbConfig config, String backupFilePath);
    }

