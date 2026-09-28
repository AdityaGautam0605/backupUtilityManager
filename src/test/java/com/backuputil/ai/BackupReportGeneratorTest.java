package com.backuputil.ai;

import com.backuputil.model.BackupResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class BackupReportGeneratorTest {
    @TempDir Path temp;

    @Test
    void failedRunDoesNotCompareHistoricalArchives() throws Exception {
        archive("20260101", "gz", 100);
        archive("20260102", "gz", 500);
        String report = report(BackupResult.Status.FAILED, temp.resolve("missing.gz"), 0);
        assertTrue(report.contains("this run did not produce a successful backup"));
        assertFalse(report.contains("%"));
        assertFalse(report.contains("Archive grew"));
        assertFalse(report.contains("looks healthy"));
    }

    @Test
    void comparesActualResultWithOlderSameCompressionArchive() throws Exception {
        archive("20260101", "gz", 100);
        archive("20260102", "zst", 50);
        Path current = archive("20260103", "gz", 200);
        archive("20260104", "gz", 900); // another run completed after the one being reported
        String report = report(BackupResult.Status.SUCCESS, current, 200);
        assertTrue(report.contains("shop_20260101_120000_backup.sql.gz"));
        assertTrue(report.contains("100.0%"));
        assertFalse(report.contains("unexpected data growth"));
    }

    @Test
    void firstSuccessDoesNotClaimVerifiedHealthAndShowsSmallThroughput() throws Exception {
        Path current = archive("20260101", "gz", 100);
        String report = report(BackupResult.Status.SUCCESS, current, 100);
        assertTrue(report.contains("no older archive"));
        assertTrue(report.contains("test restoration"));
        assertTrue(report.contains("B/s (compressed)"));
        assertFalse(report.contains("0.00 MB/s"));
    }

    @Test
    void zeroByteBaselineDoesNotProducePercentage() throws Exception {
        archive("20260101", "gz", 0);
        Path current = archive("20260102", "gz", 100);
        String report = report(BackupResult.Status.SUCCESS, current, 100);
        assertTrue(report.contains("previous archive is empty or unreadable"));
        assertFalse(report.contains("%"));
    }

    @Test
    void failedFirstRunDoesNotClaimSuccess() {
        String report = report(BackupResult.Status.FAILED, temp.resolve("missing.gz"), 0);
        assertTrue(report.contains("no usable backup was produced"));
        assertFalse(report.contains("Dump command succeeded"));
    }

    private Path archive(String day, String compression, int size) throws Exception {
        return Files.write(temp.resolve("shop_" + day + "_120000_backup.sql." + compression), new byte[size]);
    }

    private String report(BackupResult.Status status, Path path, long size) {
        BackupResult result = new BackupResult(status, "shop", "postgres", path.toString(), size,
                1000, status == BackupResult.Status.SUCCESS ? 0 : 1, null, Instant.now());
        return new BackupReportGenerator().generate(result, temp.toString());
    }
}
