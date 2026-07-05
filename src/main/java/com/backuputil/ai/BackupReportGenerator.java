package com.backuputil.ai;

import com.backuputil.model.BackupResult;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 4 — human-readable post-backup report with trends and recommendations.
 *
 * Trends are derived without any persistent store: the generator scans the output
 * directory for prior backups of the same database (timestamp + size parsed from the
 * filenames the backup pipeline already writes) and compares the latest run against
 * its history.
 */
public class BackupReportGenerator {

    private static final Pattern BACKUP_FILE =
            Pattern.compile("^(.*)_(\\d{8})_(\\d{6})_backup\\..*$");

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

        List<Path> history = listBackups(outputDir, result.getDbName());

        if (history.isEmpty()) {
            sb.append(pad("History")).append("no archives on disk yet\n");
            sb.append(recommendations(result, history, -1)).append('\n');
            return sb.toString();
        }

        long totalSize = 0;
        for (Path p : history) totalSize += safeSize(p);

        sb.append(pad("Backups on disk")).append(history.size())
                .append(" (total ").append(humanSize(totalSize)).append(")\n");

        // history is newest-first; index 0 is the run we just made.
        double deltaPct = -1;
        if (history.size() >= 2) {
            long current = safeSize(history.get(0));
            long previous = safeSize(history.get(1));
            if (previous > 0) {
                deltaPct = ((double) (current - previous) / previous) * 100.0;
                sb.append(pad("Previous archive")).append(humanSize(previous))
                        .append(String.format("  (%+.1f%% vs previous)", deltaPct)).append('\n');
            }
        } else {
            sb.append(pad("Previous archive")).append("none — this is the first backup\n");
        }

        sb.append(recommendations(result, history, deltaPct)).append('\n');
        return sb.toString();
    }

    private String recommendations(BackupResult result, List<Path> history, double deltaPct) {
        List<String> notes = new ArrayList<>();

        if (result.getStatus() != BackupResult.Status.SUCCESS) {
            notes.add("Backup did not succeed — review the error above and the AI analysis before relying on this archive.");
        } else if (result.getFileSizeBytes() == 0) {
            notes.add("Archive is 0 bytes — the dump may have produced no data; verify the source database is populated.");
        }

        if (deltaPct >= 25) {
            notes.add(String.format("Archive grew %.0f%% since the last run — investigate unexpected data growth.", deltaPct));
        } else if (deltaPct <= -25) {
            notes.add(String.format("Archive shrank %.0f%% since the last run — confirm no data was lost.", Math.abs(deltaPct)));
        }

        if (history.size() > 10) {
            notes.add("More than 10 archives are stored here — consider pruning or rotating old backups to reclaim space.");
        }

        if (notes.isEmpty()) {
            notes.add("Backup looks healthy — size and outcome are within normal range.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(pad("Recommendation")).append(notes.get(0));
        String indent = " ".repeat(18); // align continuation under the value column
        for (int i = 1; i < notes.size(); i++) {
            sb.append('\n').append(indent).append(notes.get(i));
        }
        return sb.toString();
    }

    private List<Path> listBackups(String outputDir, String dbName) {
        List<Path> result = new ArrayList<>();
        File dir = new File(outputDir);
        File[] files = dir.listFiles();
        if (files == null) return result;

        for (File f : files) {
            if (!f.isFile()) continue;
            Matcher m = BACKUP_FILE.matcher(f.getName());
            if (m.matches() && m.group(1).equalsIgnoreCase(dbName)) {
                result.add(f.toPath());
            }
        }
        result.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        return result;
    }

    // ---- formatting helpers ------------------------------------------------------

    private String throughput(BackupResult result) {
        if (result.getDurationMs() <= 0 || result.getFileSizeBytes() <= 0) return "n/a";
        double mbPerSec = (result.getFileSizeBytes() / (1024.0 * 1024)) / (result.getDurationMs() / 1000.0);
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
