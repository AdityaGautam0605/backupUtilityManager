package com.backuputil.service;
import com.backuputil.config.DbConfig;
import com.backuputil.model.BackupResult;

    public interface DatabaseService{
        boolean testConnection (DbConfig config);
        BackupResult backup(DbConfig config, String outputDir) throws Exception;
        void restore(DbConfig config, String backupFilePath);
    }

