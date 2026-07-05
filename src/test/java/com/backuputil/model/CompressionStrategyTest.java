package com.backuputil.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CompressionStrategyTest {

    @Test
    void fromFileNameDetectsEachExtension() {
        assertEquals(CompressionStrategy.GZIP,  CompressionStrategy.fromFileName("shop_db_20260101_120000_backup.sql.gz"));
        assertEquals(CompressionStrategy.ZSTD,  CompressionStrategy.fromFileName("shop_db_20260101_120000_backup.sql.zst"));
        assertEquals(CompressionStrategy.BZIP2, CompressionStrategy.fromFileName("shop_db_20260101_120000_backup.bson.bz2"));
        assertEquals(CompressionStrategy.LZ4,   CompressionStrategy.fromFileName("shop_db_20260101_120000_backup.sql.lz4"));
    }

    @Test
    void fromFileNameDefaultsToGzipForUnknownOrNull() {
        // Restore falls back to GZIP because that is what the backup side falls back to.
        assertEquals(CompressionStrategy.GZIP, CompressionStrategy.fromFileName("mystery.dat"));
        assertEquals(CompressionStrategy.GZIP, CompressionStrategy.fromFileName(null));
    }

    @Test
    void fromStringIsCaseInsensitiveAndNullSafe() {
        assertEquals(CompressionStrategy.ZSTD, CompressionStrategy.fromString("zstd"));
        assertEquals(CompressionStrategy.ZSTD, CompressionStrategy.fromString("  ZSTD "));
        assertNull(CompressionStrategy.fromString("brotli"));
        assertNull(CompressionStrategy.fromString(null));
        assertNull(CompressionStrategy.fromString(""));
    }

    @Test
    void extensionsAreStable() {
        assertEquals(".gz",  CompressionStrategy.GZIP.getExtension());
        assertEquals(".zst", CompressionStrategy.ZSTD.getExtension());
        assertEquals(".bz2", CompressionStrategy.BZIP2.getExtension());
        assertEquals(".lz4", CompressionStrategy.LZ4.getExtension());
    }

    @Test
    void gzipIsAlwaysAvailable() {
        assertEquals(true, CompressionStrategy.GZIP.isAvailable());
    }
}
