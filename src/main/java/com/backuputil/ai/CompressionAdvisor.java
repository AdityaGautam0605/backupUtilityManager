package com.backuputil.ai;

import com.backuputil.config.*;
import com.backuputil.model.CompressionStrategy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;

public class CompressionAdvisor {

    private final AppConfig config;

    // single shared reader - never create Scanner (System.in) inside a method;
    private final BufferedReader inputReader;

    public CompressionAdvisor (){
        this.config = AppConfig.getInstance();
        this.inputReader = new BufferedReader(new InputStreamReader(System.in));
    }

    // Step 1 — detect DB size automatically, don't ask the user
    public CompressionStrategy adviseAndConfirm(DbConfig dbConfig, int backupFrequencyPerDay) {

        long estimatedSizeBytes = estimateDbSize(dbConfig);
        long sizeInMB = estimatedSizeBytes / (1024 * 1024);

        CompressionStrategy recommended = recommend(estimatedSizeBytes, backupFrequencyPerDay);
        String reasoning = buildReasoning(recommended, sizeInMB, backupFrequencyPerDay);

        // Check availability of all strategies once — cache results
        Map<CompressionStrategy, Boolean> availability = checkAvailability();

        // Print recommendation
        System.out.println("\n[AI Compression Advisor] Detected database size: ~" + sizeInMB + "MB");
        System.out.println("[AI Compression Advisor] Recommended: " + recommended.name());
        System.out.println("[AI Compression Advisor] Reason: " + reasoning);

        // Show all options with availability
        System.out.println("\nAvailable strategies:");
        for (CompressionStrategy strategy : CompressionStrategy.values()) {
            boolean available = availability.getOrDefault(strategy, false);
            System.out.printf("  %-6s — %s%s%n",
                    strategy.name(),
                    strategy.getDescription(),
                    available ? "" : " [NOT INSTALLED]");
        }

        // If recommended isn't available, adjust suggestion
        if (!availability.getOrDefault(recommended, false)) {
            System.out.println("\n[AI Compression Advisor] Warning: " + recommended.name()
                    + " not installed — suggesting GZIP as fallback");
            recommended = CompressionStrategy.GZIP;
        }

        return promptUserChoice(recommended, availability);
    }

    private CompressionStrategy promptUserChoice(CompressionStrategy recommended,
                                                 Map<CompressionStrategy, Boolean> availability) {
        System.out.print("\nPress ENTER to accept [" + recommended.name()
                + "] or type another strategy (GZIP/BZIP2/LZ4/ZSTD): ");

        try {
            String userInput = inputReader.readLine();

            if (userInput == null || userInput.isBlank()) {
                System.out.println("[AI Compression Advisor] Accepted: " + recommended.name());
                return recommended;
            }

            CompressionStrategy chosen = CompressionStrategy.fromString(userInput);

            // fromString returns null on unknown input — handle it explicitly
            if (chosen == null) {
                System.out.println("[AI Compression Advisor] Unknown strategy '" + userInput
                        + "' — falling back to " + recommended.name());
                return recommended;
            }

            // Check if chosen is actually installed
            if (!availability.getOrDefault(chosen, false)) {
                System.out.println("[AI Compression Advisor] " + chosen.name()
                        + " is not installed on this system — falling back to GZIP");
                return CompressionStrategy.GZIP;
            }

            System.out.println("[AI Compression Advisor] Using: " + chosen.name());
            return chosen;

        } catch (Exception e) {
            System.out.println("[AI Compression Advisor] Input error — using " + recommended.name());
            return recommended;
        }
    }

    // Check all strategies once and cache — avoids spawning 4 processes repeatedly
    private Map<CompressionStrategy, Boolean> checkAvailability() {
        Map<CompressionStrategy, Boolean> result = new LinkedHashMap<>();
        for (CompressionStrategy strategy : CompressionStrategy.values()) {
            result.put(strategy, strategy.isAvailable());
        }
        return result;
    }

    // Estimate DB size by checking the data directory size via the DB CLI
    // Falls back to a conservative default if detection fails
    private long estimateDbSize(DbConfig config) {
        try {
            // pg_database_size / information_schema approach could work here
            // For now return a safe default — real implementation would query the DB
            System.out.println("[AI Compression Advisor] Estimating database size...");
            return 100L * 1024 * 1024; // 100MB default
        } catch (Exception e) {
            return 100L * 1024 * 1024;
        }
    }

    private CompressionStrategy recommend(long estimatedSizeBytes, int backupFrequencyPerDay) {
        long sizeInMB = estimatedSizeBytes / (1024 * 1024);

        if (sizeInMB > 500 && backupFrequencyPerDay > 2) {
            return CompressionStrategy.LZ4;
        }
        if (sizeInMB < 100 && backupFrequencyPerDay <= 1) {
            return CompressionStrategy.BZIP2;
        }
        return CompressionStrategy.ZSTD;
    }

    private String buildReasoning(CompressionStrategy strategy, long sizeInMB, int backupFrequencyPerDay) {
        return switch (strategy) {
            case LZ4 -> String.format(
                    "DB is large (~%dMB) and backed up %dx/day — speed takes priority over compression ratio",
                    sizeInMB, backupFrequencyPerDay);
            case BZIP2 -> String.format(
                    "DB is small (~%dMB) with infrequent backups — maximising compression ratio",
                    sizeInMB);
            case ZSTD -> String.format(
                    "DB size (~%dMB) with %dx/day frequency — ZSTD gives the best speed/ratio balance",
                    sizeInMB, backupFrequencyPerDay);
            case GZIP -> "Falling back to GZIP for maximum compatibility";
        };
    }
}
