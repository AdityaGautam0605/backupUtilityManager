package com.backuputil.ai;

import com.backuputil.config.AppConfig;
import com.backuputil.config.DbConfig;
import com.backuputil.model.CompressionStrategy;
import com.backuputil.model.RestoreResult;
import com.backuputil.service.DatabaseService;
import com.backuputil.util.OpenAiClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 4 — AI-guided interactive restore.
 *
 * Responsibilities:
 *  - Point-in-time selection: lists existing backups for the database (parsed from
 *    filenames) and lets the user pick which snapshot to restore.
 *  - Decompression strategy auto-detection from the file extension.
 *  - A destructive-operation confirmation gate (restore overwrites live data).
 *  - AI guidance: a short safety briefing (rule-based in mock mode, OpenAI in real mode).
 *
 * The advisor does not pick the service implementation — {@code BackupCommand} already
 * resolves the DB type and hands the matching {@link DatabaseService} in.
 */
public class RestoreAdvisor {

    // Matches "<dbName>_<yyyyMMdd>_<HHmmss>_backup.<ext...>" — group 1 is the db name
    // (which may itself contain underscores), groups 2/3 are the timestamp.
    private static final Pattern BACKUP_FILE =
            Pattern.compile("^(.*)_(\\d{8})_(\\d{6})_backup\\..*$");

    private final AppConfig config;
    private final BufferedReader inputReader;
    private final OpenAiClient openAi;

    public RestoreAdvisor() {
        this.config = AppConfig.getInstance();
        this.inputReader = new BufferedReader(new InputStreamReader(System.in));
        this.openAi = new OpenAiClient();
    }

    /**
     * Drives the full interactive restore. When {@code explicitFile} is provided the
     * point-in-time listing is skipped; otherwise the user selects a snapshot.
     */
    public RestoreResult adviseAndRestore(DatabaseService service, DbConfig dbConfig,
                                          String outputDir, String explicitFile, String dbType) {

        String chosenFile = explicitFile;

        if (chosenFile == null || chosenFile.isBlank()) {
            List<Path> candidates = listBackups(outputDir, dbConfig.getDbName(), dbType);
            if (candidates.isEmpty()) {
                System.out.println("[Restore Advisor] No backups found for '" + dbConfig.getDbName()
                        + "' in " + new File(outputDir).getAbsolutePath());
                return aborted(dbConfig, dbType, null, "No backups available to restore");
            }
            chosenFile = promptSelection(candidates);
            if (chosenFile == null) {
                return aborted(dbConfig, dbType, null, "User cancelled restore selection");
            }
        } else if (!new File(chosenFile).isFile()) {
            System.out.println("[Restore Advisor] File not found: " + chosenFile);
            return aborted(dbConfig, dbType, chosenFile, "Backup file not found");
        }

        CompressionStrategy strategy = CompressionStrategy.fromFileName(new File(chosenFile).getName());
        System.out.println("\n[Restore Advisor] Auto-detected compression: " + strategy.name()
                + " (from " + strategy.getExtension() + " extension)");

        System.out.println("\n" + guidance(dbConfig, dbType, chosenFile, strategy));

        if (!confirmDestructive(dbConfig.getDbName())) {
            return aborted(dbConfig, dbType, chosenFile, "User declined the destructive-operation confirmation");
        }

        return service.restore(dbConfig, chosenFile);
    }

    // ---- point-in-time selection -------------------------------------------------

    static List<Path> listBackups(String outputDir, String dbName, String dbType) {
        List<Path> result = new ArrayList<>();
        File dir = new File(outputDir);
        File[] files = dir.listFiles();
        if (files == null) return result;

        for (File f : files) {
            if (!f.isFile()) continue;
            Matcher m = BACKUP_FILE.matcher(f.getName());
            String suffix = "mongo".equalsIgnoreCase(dbType) ? ".bson" : ".sql";
            boolean supported = java.util.Arrays.stream(CompressionStrategy.values())
                    .anyMatch(strategy -> f.getName().endsWith(suffix + strategy.getExtension()));
            if (m.matches() && m.group(1).equals(dbName) && supported) {
                result.add(f.toPath());
            }
        }
        // Timestamp is embedded as yyyyMMdd_HHmmss, so lexical sort == chronological. Newest first.
        result.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        return result;
    }

    private String promptSelection(List<Path> candidates) {
        System.out.println("\n[Restore Advisor] Available restore points (newest first):");
        for (int i = 0; i < candidates.size(); i++) {
            Path p = candidates.get(i);
            System.out.printf("  [%d] %s   (%s, %s)%n",
                    i + 1,
                    p.getFileName(),
                    readableTimestamp(p.getFileName().toString()),
                    humanSize(safeSize(p)));
        }
        System.out.print("\nEnter the number to restore (or blank to cancel): ");

        try {
            String line = inputReader.readLine();
            if (line == null || line.isBlank()) return null;
            int idx = Integer.parseInt(line.trim());
            if (idx < 1 || idx > candidates.size()) {
                System.out.println("[Restore Advisor] Out-of-range selection — cancelling.");
                return null;
            }
            return candidates.get(idx - 1).toString();
        } catch (NumberFormatException e) {
            System.out.println("[Restore Advisor] Not a valid number — cancelling.");
            return null;
        } catch (Exception e) {
            System.out.println("[Restore Advisor] Input error — cancelling.");
            return null;
        }
    }

    private boolean confirmDestructive(String dbName) {
        System.out.print("\n[WARNING] This will replay the archive into the live database '" + dbName
                + "' and may overwrite existing data.\nType 'yes' to proceed: ");
        try {
            String line = inputReader.readLine();
            return line != null && line.trim().equalsIgnoreCase("yes");
        } catch (Exception e) {
            return false;
        }
    }

    // ---- AI guidance -------------------------------------------------------------

    private String guidance(DbConfig dbConfig, String dbType, String file, CompressionStrategy strategy) {
        if (!config.isMockAi() && config.isAiEnabled()) {
            String ai = aiGuidance(dbConfig, dbType, file, strategy);
            if (ai != null) return "[AI Restore Guidance]\n" + ai;
        }
        return ruleBasedGuidance(dbConfig, dbType, strategy);
    }

    private String ruleBasedGuidance(DbConfig dbConfig, String dbType, CompressionStrategy strategy) {
        return "[Restore Guidance]\n"
                + " - Target      : " + dbConfig.getDbName() + " (" + dbType + ") on "
                + dbConfig.getHost() + ":" + dbConfig.getPort() + "\n"
                + " - Decompress  : " + strategy.name() + " (auto-detected)\n"
                + " - Risk        : existing objects may conflict; MySQL/MongoDB failures can leave partial changes.\n"
                + " - Recommended : use an empty target database and take a fresh safety backup first.\n"
                + " - Validation  : archive is checked, then streamed without temporary files; keep it unchanged.\n"
                + ("mongo".equalsIgnoreCase(dbType)
                    ? " - Scope       : only namespaces in '" + dbConfig.getDbName() + "' are restored; no database renaming."
                    : " - Source      : use a trusted SQL dump from the selected engine.");
    }

    private String aiGuidance(DbConfig dbConfig, String dbType, String file, CompressionStrategy strategy) {
        String prompt = String.format(
                "I am about to restore a %s database named '%s' from the backup file '%s' "
                        + "(compressed with %s). In 2-3 concise sentences, give a safety checklist "
                        + "and the main risks. Do not ask for the password.",
                dbType, dbConfig.getDbName(), new File(file).getName(), strategy.name());

        // Returns null on any failure, so guidance() falls back to the rule-based briefing.
        return openAi.generate(prompt);
    }

    // ---- helpers -----------------------------------------------------------------

    private RestoreResult aborted(DbConfig dbConfig, String dbType, String file, String reason) {
        return new RestoreResult(RestoreResult.Status.ABORTED,
                dbConfig.getDbName(), dbType, file,
                0, 0, -1, reason, java.time.Instant.now());
    }

    private long safeSize(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return 0;
        }
    }

    private String readableTimestamp(String fileName) {
        Matcher m = BACKUP_FILE.matcher(fileName);
        if (m.matches()) {
            String d = m.group(2); // yyyyMMdd
            String t = m.group(3); // HHmmss
            return String.format("%s-%s-%s %s:%s:%s",
                    d.substring(0, 4), d.substring(4, 6), d.substring(6, 8),
                    t.substring(0, 2), t.substring(2, 4), t.substring(4, 6));
        }
        return "unknown time";
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
