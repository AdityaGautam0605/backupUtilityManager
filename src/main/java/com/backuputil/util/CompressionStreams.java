package com.backuputil.util;

import com.backuputil.model.CompressionStrategy;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Centralised decompression helpers for the restore pipeline.
 *
 * Unlike the backup side (which silently falls back to GZIP when a compression
 * library is missing), decompression must NOT fall back blindly — reading a ZSTD
 * archive as GZIP would corrupt the restore. So if the required library is absent
 * we fail loudly. The one safe exception is a magic-byte check: because the backup
 * side currently falls back to GZIP whenever the BZIP2/LZ4/ZSTD libraries aren't on
 * the classpath, a file can carry a ".zst"/".bz2"/".lz4" extension while actually
 * containing GZIP bytes. We detect that real case via the GZIP magic header.
 */
public final class CompressionStreams {

    private static final int GZIP_MAGIC_1 = 0x1f;
    private static final int GZIP_MAGIC_2 = 0x8b;

    private CompressionStreams() {
    }

    /**
     * Wraps a raw input stream in the decompressor implied by {@code expected},
     * overriding to GZIP when the file's magic bytes say it is actually GZIP.
     */
    public static InputStream wrapDecompress(InputStream rawIn, CompressionStrategy expected) throws Exception {
        BufferedInputStream in = new BufferedInputStream(rawIn);

        // Peek the first two bytes without consuming them.
        in.mark(2);
        int b1 = in.read();
        int b2 = in.read();
        in.reset();

        boolean looksGzip = (b1 == GZIP_MAGIC_1 && b2 == GZIP_MAGIC_2);
        CompressionStrategy effective = expected;

        if (looksGzip && expected != CompressionStrategy.GZIP) {
            System.out.println("[Restore] File magic indicates GZIP despite the " + expected.getExtension()
                    + " extension — decompressing as GZIP");
            effective = CompressionStrategy.GZIP;
        }

        return switch (effective) {
            case GZIP -> new GZIPInputStream(in);
            case BZIP2 -> reflectiveInput(
                    "org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream", in, "BZIP2");
            case LZ4 -> reflectiveInput(
                    "net.jpountz.lz4.LZ4FrameInputStream", in, "LZ4");
            case ZSTD -> reflectiveInput(
                    "com.github.luben.zstd.ZstdInputStream", in, "ZSTD");
        };
    }

    private static InputStream reflectiveInput(String className, InputStream in, String label) throws Exception {
        try {
            Class<?> clazz = Class.forName(className);
            return (InputStream) clazz.getConstructor(InputStream.class).newInstance(in);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("[Restore] " + label + " library is not on the classpath — cannot "
                    + "decompress this archive. Add the matching dependency to pom.xml and retry.");
        }
    }
}
