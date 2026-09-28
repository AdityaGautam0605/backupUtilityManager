package com.backuputil.ai;

import com.backuputil.model.BackupResult;


import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;

import java.util.Date;
import java.util.List;



/**
 * Phase 4 — human-readable post-backup report with trends and recommendations.
 *
 * Trends are derived without any persistent store: the generator scans the output
 * directory for prior backups of the same database (timestamp + size parsed from the
 * filenames the backup pipeline already writes) and compares the latest run against
 * its history.
 */
public class BackupReportGenerator {


    /** Builds the full report text for a finished backup run. */
    public String generate(BackupResult result, String outputDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("================= BACKUP REPORT =================\n");
        sb.append(pad("Status")).append(result.getStatus()).append('\n');
        sb.append(pad("Database")).append(result.getDbName())
                .append(" (").append(result.getDbType()).append(")\n");
        sb.append(pad("Timestamp")).append(result.getTimestamp()).append('\n');
        sb.append(pad("Output")).append(result.getOutputPath()).append('\n');
        sb.append(pad("Archive size")).append(humanSize(result.getFileSizeBytes())).append('\n');
        sb.append(pad("Duration")).append(result.getDurationMs()).append(" ms\n");
        sb.append(pad("Throughput")).append(throughput(result)).append('\n');
        sb.append(pad("Exit code")).append(result.getExitCode()).append('\n');
        if (result.getErrorMessage() != null) {
            sb.append(pad("Error")).append(result.getErrorMessage()).append('\n');
        }

        sb.append(buildTrends(result, outputDir));
        sb.append("================================================");
        return sb.toString();
    }

    /** Writes the report next to the backups as {@code <dbName>_<timestamp>_report.txt}. */
    public Path writeToFile(String report, String outputDir, String dbName) throws Exception {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        Path path = Paths.get(outputDir, dbName + "_" + ts + "_report.txt");
        Files.writeString(path, report);
        return path;
    }

    // ---- trends ------------------------------------------------------------------

    private String buildTrends(BackupResult result, String outputDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("------------------- TRENDS ---------------------\n");

        List<Path> history = RestoreAdvisor.listBackups(outputDir, result.getDbName(), result.getDbType());

        if (history.isEmpty()) {
            sb.append(pad("History")).append("no archives on disk yet\n");
            sb.append(recommendations(result, history, Double.NaN)).append('\n');
            return sb.toString();
        }

        long totalSize = 0;
        for (Path p : history) totalSize += safeSize(p);

        sb.append(pad("Backups on disk")).append(history.size())
                .append(" (total ").append(humanSize(totalSize)).append(")\n");

        double deltaPct = Double.NaN;
        if (result.getStatus() != BackupResult.Status.SUCCESS) {
            sb.append(pad("Size comparison")).append("unavailable — this run did not produce a successful backup\n");
        } else if (result.getOutputPath() == null || !Files.isRegularFile(Path.of(result.getOutputPath()))) {
            sb.append(pad("Size comparison")).append("unavailable — current archive is missing\n");
        } else {
            Path current = Path.of(result.getOutputPath()).toAbsolutePath().normalize();
            String name = current.getFileName().toString();
            String extension = name.substring(name.lastIndexOf('.') + 1);
            // Select a genuinely older file, rather than assuming the newest is this run.
            Path previous = history.stream()
                    .filter(p -> !p.toAbsolutePath().normalize().equals(current))
                    .filter(p -> p.getFileName().toString().compareTo(name) < 0)
                    .filter(p -> p.getFileName().toString().endsWith("." + extension))
                    .findFirst().orElse(null);
            if (previous == null) {
                sb.append(pad("Previous archive")).append("no older archive with the same format/compression\n");
            } else if (safeSize(previous) <= 0) {
                sb.append(pad("Size comparison")).append("unavailable — previous archive is empty or unreadable\n");
            } else {
                long previousSize = safeSize(previous);
                deltaPct = ((double) (result.getFileSizeBytes() - previousSize) / previousSize) * 100.0;
                sb.append(pad("Previous archive")).append(previous.getFileName()).append(" ( ")
                        .append(humanSize(previousSize)).append(")")
                        .append(String.format("  (%+.1f%% vs previous)", deltaPct)).append('\n');
            }
        }

        sb.append(recommendations(result, history, deltaPct)).append('\n');
        return sb.toString();
    }

    private String recommendations(BackupResult result, List<Path> history, double deltaPct) {
        List<String> notes = new ArrayList<>();

        if (result.getStatus() != BackupResult.Status.SUCCESS) {
            notes.add("Backup did not succeed — no usable backup was produced by this run. Review the error above.");
        } else if (result.getFileSizeBytes() == 0) {
            notes.add("Archive is 0 bytes — the dump may have produced no data; verify the source database is populated.");
        }

        if (deltaPct >= 25) {
            notes.add(String.format("Compressed archive grew %.0f%% versus the previous comparable archive; confirm this matches expected changes.", deltaPct));
        } else if (deltaPct <= -25) {
            notes.add(String.format("Compressed archive shrank %.0f%% versus the previous comparable archive; confirm this matches expected changes.", Math.abs(deltaPct)));
        }

        if (history.size() > 10) {
            notes.add("More than 10 archives are stored here — consider pruning or rotating old backups to reclaim space.");
        }

        if (notes.isEmpty()) {
            notes.add("Dump command succeeded. Archive size alone does not verify data completeness; test restoration.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(pad("Recommendation")).append(notes.get(0));
        String indent = " ".repeat(18); // align continuation under the value column
        for (int i = 1; i < notes.size(); i++) {
            sb.append('\n').append(indent).append(notes.get(i));
        }
        return sb.toString();
    }

    // ---- formatting helpers ------------------------------------------------------

    private String throughput(BackupResult result) {
        if (result.getStatus() != BackupResult.Status.SUCCESS || result.getDurationMs() <= 0 || result.getFileSizeBytes() <= 0) return "n/a";
        double bytesPerSec = result.getFileSizeBytes() / (result.getDurationMs() / 1000.0);
        if (bytesPerSec < 1024) return String.format("%.1f B/s (compressed)", bytesPerSec);
        if (bytesPerSec < 1024 * 1024) return String.format("%.2f KB/s (compressed)", bytesPerSec / 1024);
        double mbPerSec = bytesPerSec / (1024 * 1024);
        return String.format("%.2f MB/s (compressed)", mbPerSec);
    }

    private long safeSize(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return 0;
        }
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private String pad(String label) {
        return String.format("%-16s: ", label);
    }
}
