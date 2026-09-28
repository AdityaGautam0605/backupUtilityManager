package com.backuputil.ai;

import com.backuputil.config.*;
import com.backuputil.model.CompressionStrategy;
import com.backuputil.service.DatabaseService;



import java.util.LinkedHashMap;
import java.util.Map;

public class CompressionAdvisor {

    private final AppConfig config;




    public CompressionAdvisor (){
        this.config = AppConfig.getInstance();

    }

    // Step 1 — detect DB size automatically, don't ask the user
    public CompressionStrategy adviseAndConfirm(DatabaseService service, DbConfig dbConfig, int backupFrequencyPerDay) {

        long estimatedSizeBytes = estimateDbSize(service, dbConfig);

        CompressionStrategy recommended = recommend(estimatedSizeBytes, backupFrequencyPerDay);
        String reasoning = buildReasoning(recommended, estimatedSizeBytes, backupFrequencyPerDay);

        // Check availability of all strategies once — cache results
        Map<CompressionStrategy, Boolean> availability = checkAvailability();

        // Print recommendation
        String sizeLabel = estimatedSizeBytes < 0 ? "unknown" : "~" + (estimatedSizeBytes / (1024 * 1024)) + "MB";
        System.out.println("\n[AI Compression Advisor] Detected database size: " + sizeLabel);
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
            String userInput = com.backuputil.util.ConsoleInput.readLine("");

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

    // Ask the database itself how big it is (pg_database_size / information_schema / dbStats).
    // Returns a negative sentinel when the size can't be determined (mock mode, query failure).
    private long estimateDbSize(DatabaseService service, DbConfig config) {
        System.out.println("[AI Compression Advisor] Estimating database size...");
        long size = service.estimateSizeBytes(config);
        if (size < 0) {
            System.out.println("[AI Compression Advisor] Size unavailable — using a balanced default");
        }
        return size;
    }

    // Thresholds use inclusive boundaries so every real size lands in exactly one branch
    // (the previous "< 100" / "> 500" pair left a gap that always fell through to ZSTD).
    private CompressionStrategy recommend(long estimatedSizeBytes, int backupFrequencyPerDay) {
        // Unknown size — play it safe with the balanced default.
        if (estimatedSizeBytes < 0) {
            return CompressionStrategy.ZSTD;
        }

        long sizeInMB = estimatedSizeBytes / (1024 * 1024);
        boolean large = sizeInMB >= 500;
        boolean small = sizeInMB <= 100;
        boolean frequent = backupFrequencyPerDay >= 2;

        if (large && frequent) {
            return CompressionStrategy.LZ4;   // big + often → speed dominates
        }
        if (small && !frequent) {
            return CompressionStrategy.BZIP2; // small + rare → squeeze hardest, time is cheap
        }
        return CompressionStrategy.ZSTD;      // everything in between → balanced
    }

    private String buildReasoning(CompressionStrategy strategy, long estimatedSizeBytes, int backupFrequencyPerDay) {
        String size = estimatedSizeBytes < 0
                ? "unknown size"
                : String.format("~%dMB", estimatedSizeBytes / (1024 * 1024));
        return switch (strategy) {
            case LZ4 -> String.format(
                    "DB is large (%s) and backed up %dx/day — speed takes priority over compression ratio",
                    size, backupFrequencyPerDay);
            case BZIP2 -> String.format(
                    "DB is small (%s) with infrequent backups — maximising compression ratio",
                    size);
            case ZSTD -> String.format(
                    "DB size %s with %dx/day frequency — ZSTD gives the best speed/ratio balance",
                    size, backupFrequencyPerDay);
            case GZIP -> "Falling back to GZIP for maximum compatibility";
        };
    }
}
