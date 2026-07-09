package com.backuputil.ai;

import com.backuputil.config.AppConfig;
import com.backuputil.model.BackupResult;
import com.backuputil.util.GeminiClient;

public class RootCauseAnalyser {

    private final AppConfig config;
    private final GeminiClient gemini;

    public RootCauseAnalyser(){
        this.config = AppConfig.getInstance();
        this.gemini = new GeminiClient();
    }

    public String analyse(BackupResult result){
        if(!config.isAiEnabled()){
            return "[AI disabled — set GEMINI_API_KEY to enable analysis]";
        }
        if(config.isMockAi()){
            return generateMockAnalysis(result);
        }

        String analysis = gemini.generate(buildPrompt(result));
        return analysis != null ? analysis.trim()
                : "[AI] Analysis unavailable — the Gemini request failed";
    }

    private String generateMockAnalysis (BackupResult result){
        if (result.getStatus() == BackupResult.Status.SUCCESS){
            return String.format(
                    "[MOCK AI] Backup completed successfully for '%s' (%s). " +
                            "Archive size: %s, duration: %dms. No issues detected.",
                    result.getDbName(),
                    result.getDbType(),
                    formatSize(result.getFileSizeBytes()),
                    result.getDurationMs()
            );
        }else {
            return String.format(
                    "[MOCK AI] Backup failed for '%s' (%s) with exit code %d. " +
                            "Error: %s. " +
                            "Suggested fix: verify database credentials and connectivity.",
                    result.getDbName(),
                    result.getDbType(),
                    result.getExitCode(),
                    result.getErrorMessage() != null ? result.getErrorMessage() : "unknown error"
            );
        }
    }

    // Plain-text prompt (real newlines) — GeminiClient JSON-escapes it before sending.
    private String buildPrompt (BackupResult result){
        if(result.getStatus() == BackupResult.Status.SUCCESS){
            return String.format(
                    "A database backup completed successfully with these details:\n" +
                            "- Database: %s (type: %s)\n" +
                            "- Archive size: %s\n" +
                            "- Duration: %dms\n" +
                            "- Output path: %s\n" +
                            "- Timestamp: %s\n\n" +
                            "Provide a concise 2-3 sentence summary of the backup health " +
                            "and any recommendations based on the size and duration.",
                    result.getDbName(), result.getDbType(),
                    formatSize(result.getFileSizeBytes()),
                    result.getDurationMs(),
                    result.getOutputPath(),
                    result.getTimestamp()
            );
        } else {
            return String.format(
                    "A database backup failed with these details:\n" +
                            "- Database: %s (type: %s)\n" +
                            "- Exit code: %d\n" +
                            "- Error message: %s\n" +
                            "- Duration before failure: %dms\n" +
                            "- Timestamp: %s\n\n" +
                            "Provide a concise root cause analysis and exact steps to fix this. " +
                            "Be specific to the database type and error message.",
                    result.getDbName(), result.getDbType(),
                    result.getExitCode(),
                    result.getErrorMessage() != null ? result.getErrorMessage() : "none",
                    result.getDurationMs(),
                    result.getTimestamp()
            );
        }
    }

    private String formatSize (long bytes){
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
