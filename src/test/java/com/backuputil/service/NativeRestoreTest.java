package com.backuputil.service;

import com.backuputil.config.DbConfig;
import com.backuputil.model.RestoreResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class NativeRestoreTest {
    @TempDir Path temp;
    private final DbConfig config = new DbConfig("localhost", 5432, "admin", "secret", "shop", false);

    @ParameterizedTest
    @ValueSource(strings = {"gz", "bz2", "lz4", "zst"})
    void validatesAndDeliversAllCompressionFormats(String extension) throws Exception {
        byte[] sql = "CREATE TABLE restored(id int);".getBytes(StandardCharsets.UTF_8);
        Path archive = archive("snapshot.sql." + extension, sql);
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        RestoreResult result = NativeRestore.restore(config, archive.toString(), "postgres", builder -> {
            assertEquals(ProcessBuilder.Redirect.PIPE, builder.redirectInput());
            assertTrue(builder.command().contains("--set=ON_ERROR_STOP=on"));
            assertTrue(builder.command().contains("--single-transaction"));
            assertTrue(builder.command().contains("--file=-"));
            assertTrue(builder.command().contains("-X"));
            assertTrue(builder.command().contains("--no-password"));
            assertEquals("secret", builder.environment().get("PGPASSWORD"));
            assertFalse(builder.command().contains("secret"));
            return new FinishedProcess(0, "") {
                @Override public OutputStream getOutputStream() { return received; }
            };
        });
        assertEquals(RestoreResult.Status.SUCCESS, result.getStatus());
        assertEquals(sql.length, result.getBytesRestored());
        assertArrayEquals(sql, received.toByteArray());
        assertTrue(Files.exists(archive));
    }

    @Test
    void corruptTrailerIsRejectedBeforeLaunchingClient() throws Exception {
        Path archive = archive("bad.sql.gz", "CREATE TABLE unsafe(id int);".getBytes());
        byte[] bytes = Files.readAllBytes(archive);
        bytes[bytes.length - 8] ^= 1; // CRC corruption after a complete, executable SQL statement
        Files.write(archive, bytes);
        assertNotLaunched(config, archive.toString(), "postgres");
    }

    @Test
    void truncatedArchiveIsRejectedBeforeLaunchingClient() throws Exception {
        Path archive = archive("truncated.sql.gz", "SELECT 1;".getBytes());
        byte[] bytes = Files.readAllBytes(archive);
        Files.write(archive, java.util.Arrays.copyOf(bytes, bytes.length - 4));
        assertNotLaunched(config, archive.toString(), "postgres");
    }

    @ParameterizedTest
    @ValueSource(strings = {"gz", "bz2", "lz4", "zst"})
    void emptyPayloadIsRejected(String extension) throws Exception {
        assertNotLaunched(config, archive("empty.sql." + extension, new byte[0]).toString(), "postgres");
    }

    @Test
    void invalidInputsReturnAbortedRatherThanThrowing() {
        assertNotLaunched(null, null, "postgres");
        assertNotLaunched(config, null, "postgres");
        assertNotLaunched(config, "", "postgres");
        assertNotLaunched(config, "\u0000", "postgres");
        assertNotLaunched(config, temp.resolve("missing.gz").toString(), "postgres");
        assertNotLaunched(config, temp.toString(), "postgres");
    }

    @Test
    void keepsGzipMagicFallbackForOlderMislabelledBackups() throws Exception {
        Path gz = archive("old.sql.gz", "SELECT 1;".getBytes());
        Path zst = Files.move(gz, temp.resolve("old.sql.zst"));
        assertEquals(RestoreResult.Status.SUCCESS,
                NativeRestore.restore(config, zst.toString(), "postgres", b -> new FinishedProcess(0, "")).getStatus());
    }

    @Test
    void nativeSqlFailureRetainsExitCodeAndDiagnostics() throws Exception {
        Path archive = archive("bad.sql.gz", "INVALID SQL;".getBytes());
        RestoreResult result = NativeRestore.restore(config, archive.toString(), "postgres", builder -> {
            return new FinishedProcess(3, "ERROR: syntax error");
        });
        assertEquals(RestoreResult.Status.FAILED, result.getStatus());
        assertEquals(3, result.getExitCode());
        assertTrue(result.getErrorMessage().contains("syntax error"));
        assertEquals(0, result.getBytesRestored());
        assertTrue(Files.exists(archive));
    }

    @Test
    void startupFailurePreservesSourceArchive() throws Exception {
        Path archive = archive("ok.sql.gz", "SELECT 1;".getBytes());
        RestoreResult result = NativeRestore.restore(config, archive.toString(), "postgres", builder -> {
            throw new IOException("client missing");
        });
        assertEquals(RestoreResult.Status.ABORTED, result.getStatus());
        assertTrue(Files.exists(archive));
    }

    @Test
    void mongoRestrictsNamespacesAndStopsOnErrors() throws Exception {
        Path archive = archive("shop_20260709_120000_backup.bson.gz", new byte[]{1, 2, 3});
        RestoreResult result = NativeRestore.restore(config, archive.toString(), "mongo", builder -> {
            assertTrue(builder.command().contains("--nsInclude=shop.*"));
            assertTrue(builder.command().contains("--stopOnError"));
            assertFalse(builder.command().contains("--drop"));
            return new FinishedProcess(1, "duplicate key error");
        });
        assertEquals(RestoreResult.Status.FAILED, result.getStatus());
        assertTrue(result.getErrorMessage().contains("duplicate key"));
    }

    @Test
    void rejectsKnownMongoDatabaseMismatchAndWrongArchiveFamily() throws Exception {
        Path mongo = archive("other_20260709_120000_backup.bson.gz", new byte[]{1});
        assertNotLaunched(config, mongo.toString(), "mongo");
        assertNotLaunched(config, mongo.toString(), "postgres");
        Path sql = archive("shop_20260709_120000_backup.sql.gz", "SELECT 1;".getBytes());
        assertNotLaunched(config, sql.toString(), "mongo");
    }

    @Test
    void rejectsTargetNamesThatCouldChangeRestoreScope() throws Exception {
        Path archive = archive("snapshot.gz", new byte[]{1});
        DbConfig wildcard = new DbConfig("localhost", 27017, "admin", "secret", "*", false);
        assertNotLaunched(wildcard, archive.toString(), "mongo");
        DbConfig connection = new DbConfig("localhost", 5432, "admin", "secret", "host=elsewhere dbname=other", false);
        assertNotLaunched(connection, archive.toString(), "postgres");
    }

    @Test
    void mysqlUsesBatchModeWithoutLocalDefaultsThatCouldEnableForce() throws Exception {
        Path archive = archive("snapshot.sql.gz", "SELECT 1;".getBytes());
        RestoreResult result = NativeRestore.restore(config, archive.toString(), "mysql", builder -> {
            assertEquals("--no-defaults", builder.command().get(1));
            assertTrue(builder.command().contains("--batch"));
            assertTrue(builder.command().contains("--database=shop"));
            assertFalse(builder.command().contains("--force"));
            assertFalse(builder.command().contains("secret"));
            assertEquals("secret", builder.environment().get("MYSQL_PWD"));
            return new FinishedProcess(1, "SQL failed");
        });
        assertEquals(RestoreResult.Status.FAILED, result.getStatus());
    }

    @Test
    void interruptionKillsChildAndReleasesSource() throws Exception {
        Path archive = archive("snapshot.sql.gz", "SELECT 1;".getBytes());
        AtomicBoolean killed = new AtomicBoolean();
        FinishedProcess child = new FinishedProcess(0, "") {
            @Override public int waitFor() throws InterruptedException {
                if (!killed.get()) throw new InterruptedException("cancelled");
                return 1;
            }
            @Override public boolean isAlive() { return !killed.get(); }
            @Override public Process destroyForcibly() { killed.set(true); return this; }
        };
        try {
            RestoreResult result = NativeRestore.restore(config, archive.toString(), "postgres", builder -> {
                return child;
            });
            assertEquals(RestoreResult.Status.FAILED, result.getStatus());
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(killed.get());
            Thread.interrupted();
            // An exclusive lock is available again after both workers have stopped.
            try (var channel = java.nio.channels.FileChannel.open(archive, java.nio.file.StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                assertNotNull(lock);
            }
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void drainsLargeDiagnosticsWithBoundedCapture() throws Exception {
        Path archive = archive("snapshot.sql.gz", "SELECT 1;".getBytes());
        RestoreResult result = NativeRestore.restore(config, archive.toString(), "postgres",
                builder -> new FinishedProcess(3, "x".repeat(200_000)));
        assertEquals(RestoreResult.Status.FAILED, result.getStatus());
        assertTrue(result.getErrorMessage().length() < 66_000);
        assertTrue(result.getErrorMessage().contains("output truncated"));
    }

    private void assertNotLaunched(DbConfig db, String archive, String engine) {
        RestoreResult result = NativeRestore.restore(db, archive, engine, builder -> {
            fail("Invalid restore must never start a database client");
            return null;
        });
        assertEquals(RestoreResult.Status.ABORTED, result.getStatus());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    void sourceChangeAfterValidationNeverReportsSuccess() throws Exception {
        Path archive = archive("changed.sql.gz", "SELECT 1;".getBytes());
        var modified = Files.getLastModifiedTime(archive);
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        RestoreResult result = NativeRestore.restore(config, archive.toString(), "postgres", builder -> {
            Files.setLastModifiedTime(archive, java.nio.file.attribute.FileTime.fromMillis(modified.toMillis() + 5000));
            return new FinishedProcess(0, "") {
                @Override public OutputStream getOutputStream() { return received; }
            };
        });
        assertEquals(RestoreResult.Status.FAILED, result.getStatus());
        assertTrue(result.getErrorMessage().contains("changed"));
        assertEquals(0, received.size());
    }

    @Test
    void streamingFailureKillsClientBeforeSignallingEof() throws Exception {
        Path archive = archive("broken.sql.gz", "SELECT 1;".getBytes());
        AtomicBoolean killed = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        java.util.concurrent.CountDownLatch exited = new java.util.concurrent.CountDownLatch(1);
        FinishedProcess child = new FinishedProcess(0, "") {
            private final OutputStream input = new OutputStream() {
                @Override public void write(int b) throws IOException { throw new IOException("pipe failed"); }
                @Override public void close() {
                    assertTrue(killed.get(), "Client must be killed before EOF could commit partial input");
                    closed.set(true);
                }
            };
            @Override public OutputStream getOutputStream() { return input; }
            @Override public int waitFor() throws InterruptedException { exited.await(); return 1; }
            @Override public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
                return exited.await(timeout, unit);
            }
            @Override public boolean isAlive() { return !killed.get(); }
            @Override public Process destroyForcibly() { killed.set(true); exited.countDown(); return this; }
        };
        RestoreResult result = NativeRestore.restore(config, archive.toString(), "postgres", builder -> child);
        assertEquals(RestoreResult.Status.FAILED, result.getStatus());
        assertTrue(result.getErrorMessage().contains("pipe failed"));
        assertTrue(killed.get());
        assertTrue(closed.get());
    }

    @Test
    void largeRestoreNeedsNeitherTemporaryDirectoryNorPayloadSizedHeap() throws Exception {
        Path archive = temp.resolve("large.sql.gz");
        byte[] block = new byte[8192];
        java.util.Arrays.fill(block, (byte) 'x');
        try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(archive))) {
            for (int i = 0; i < 8192; i++) output.write(block); // 64 MiB decompressed, 32 MiB heap below
        }
        Path log = temp.resolve("probe.log");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process probe = new ProcessBuilder(javaExecutable, "-Xmx32m",
                "-Djava.io.tmpdir=" + temp.resolve("does-not-exist"), "-cp", System.getProperty("java.class.path"),
                StreamingProbe.class.getName(), archive.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(probe.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, probe.exitValue(), Files.readString(log));
            assertFalse(Files.exists(temp.resolve("does-not-exist")));
        } finally {
            if (probe.isAlive()) probe.destroyForcibly().waitFor();
        }
    }

    public static class StreamingProbe {
        public static void main(String[] args) {
            DbConfig db = new DbConfig("localhost", 5432, "admin", "secret", "shop", false);
            RestoreResult result = NativeRestore.restore(db, args[0], "postgres", builder -> new FinishedProcess(0, ""));
            if (result.getStatus() != RestoreResult.Status.SUCCESS || result.getBytesRestored() != 64L * 1024 * 1024) {
                throw new IllegalStateException("Streaming restore failed: " + result.getErrorMessage());
            }
        }
    }

    private Path archive(String name, byte[] bytes) throws Exception {
        Path file = temp.resolve(name);
        try (OutputStream raw = Files.newOutputStream(file);
             OutputStream compressed = name.endsWith(".bz2")
                     ? new org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream(raw)
                     : name.endsWith(".lz4") ? new net.jpountz.lz4.LZ4FrameOutputStream(raw)
                     : name.endsWith(".zst") ? new com.github.luben.zstd.ZstdOutputStream(raw)
                     : new GZIPOutputStream(raw)) {
            compressed.write(bytes);
        }
        return file;
    }

    private static class FinishedProcess extends Process {
        private final int code;
        private final InputStream output;
        FinishedProcess(int code, String output) {
            this.code = code;
            this.output = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
        }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return output; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() throws InterruptedException { return code; }
        @Override public int exitValue() { return code; }
        @Override public void destroy() {}
        @Override public boolean isAlive() { return false; }
    }
}
