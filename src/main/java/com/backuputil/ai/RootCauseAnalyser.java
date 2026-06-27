package com.backuputil.ai;

import com.backuputil.config.AppConfig;
import com.backuputil.model.BackupResult;
import com.backuputil.util.ClaudeResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class RootCauseAnalyser {

    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-3-5-haiku-20241022";
    private final AppConfig config;
    private final HttpClient httpClient;

    public RootCauseAnalyser(){
        this.config = AppConfig.getInstance();
        this.httpClient = HttpClient.newHttpClient();
    }

    public String analyse(BackupResult result){
        if(!config.isAiEnabled()){
            return "[AI disabled — set ANTHROPIC_API_KEY to enable analysis]";
        }

        if(config.isMockAi()){
            return generateMockAnalysis(result);
        }

        return callClaudeApi(result);
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

    private String callClaudeApi(BackupResult result){
        try{
            String prompt = buildPrompt(result);

            // build the JSON request body manually - no extra dependencies needed
            String requestBody = String.format("""
                {
                    "model": "%s",
                    "max_tokens": 1024,
                    "messages": [
                        {
                            "role": "user",
                            "content": "%s"
                        }
                    ]
                }
                """, MODEL, escapeJson(prompt));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ANTHROPIC_API_URL))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", config.getAnthropicApiKey())
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200){
                return parseClaudeResponse(response.body());
            }else{
                System.err.println("[AI] API call failed with status: " + response.statusCode());
                return "[AI] Analysis unavailable — API error: " + response.statusCode();
            }

        } catch (Exception e){
            System.err.println("[AI] Analysis failed: " + e.getMessage());
            return "[AI] Analysis unavailable - " + e.getMessage();
        }
    }

    private String buildPrompt (BackupResult result){
        if(result.getStatus() == BackupResult.Status.SUCCESS){
            return String.format(
                    "A database backup completed successfully with these details:\\n" +
                            "- Database: %s (type: %s)\\n" +
                            "- Archive size: %s\\n" +
                            "- Duration: %dms\\n" +
                            "- Output path: %s\\n" +
                            "- Timestamp: %s\\n\\n" +
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
                    "A database backup failed with these details:\\n" +
                            "- Database: %s (type: %s)\\n" +
                            "- Exit code: %d\\n" +
                            "- Error message: %s\\n" +
                            "- Duration before failure: %dms\\n" +
                            "- Timestamp: %s\\n\\n" +
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

    // Extracts the text content from Claude's JSON response (escape-aware, no external lib)
    private String parseClaudeResponse(String responseBody){
        String text = ClaudeResponse.extractText(responseBody);
        return text != null ? text : "[AI] Could not parse response";
    }

    // escape special characters for JSON string embedding
    private String escapeJson(String text){
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String formatSize (long bytes){
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

}
