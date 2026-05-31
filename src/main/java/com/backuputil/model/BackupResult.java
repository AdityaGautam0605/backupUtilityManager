package com.backuputil.model;
import java.time.Instant;

public class BackupResult {

    public enum Status {SUCCESS, FAILED, ABORTED}

    private final Status status;
    private final String dbName;
    private final String dbType;
    private final String outputPath;
    private final long fileSizeBytes;
    private final long durationMs;
    private final int exitCode;
    private final String errorMessage;
    private final Instant timestamp;

    public BackupResult (Status status, String dbName, String dbType, String outputPath,
                         long fileSizeBytes, long durationMs, int exitCode, String errorMessage,
                         Instant timestamp){
        this.status= status;
        this.dbName = dbName;
        this.dbType = dbType;
        this.outputPath = outputPath;
        this.fileSizeBytes = fileSizeBytes;
        this.durationMs = durationMs;
        this.exitCode = exitCode;
        this.errorMessage = errorMessage;
        this.timestamp = timestamp;
    }

    public Status getStatus() {return status;}
    public String getDbName(){return dbName;}
    public String getDbType() {return dbType;}
    public String getOutputPath() {return outputPath;}
    public long getFileSizeBytes() {return fileSizeBytes;}
    public long getDurationMs(){return durationMs;}
    public int getExitCode(){return exitCode;}
    public String getErrorMessage() {return errorMessage;}
    public Instant getTimestamp() {return timestamp;}

    @Override
    public String toString(){
        return String.format("[%s] %s | db: %s (%s) | size: %d bytes | duration: %dms | exit: %d | error: %s",
                timestamp, status, dbName, dbType, fileSizeBytes, durationMs, exitCode,
                errorMessage != null ? errorMessage : "none");
    }

}
