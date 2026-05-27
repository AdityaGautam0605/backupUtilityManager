package com.backuputil.service.impl;
import com.backuputil.config.DbConfig;

    public interface DatabaseService{
        boolean testConnection (DbConfig config);
        void backup(DbConfig config, String outputDir) throws Exception;
        void restore(DbConfig config, String backupFilePath);
    }

