package com.backuputil.service;

import com.backuputil.config.DbConfig;
import com.backuputil.model.BackupResult;
import com.backuputil.model.CompressionStrategy;
import com.backuputil.model.RestoreResult;
import com.backuputil.service.impl.PostgresService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Opt in with RESTORE_TEST_PG_BIN and put that directory on PATH. Never uses an existing server. */
@EnabledIfEnvironmentVariable(named = "RESTORE_TEST_PG_BIN", matches = ".+")
class PostgresRestoreIntegrationTest {
    private static Path root;
    private static Path data;
    private static DbConfig config;
    private static boolean initialized;

    @BeforeAll
    static void startIsolatedServer() throws Exception {
        Files.createDirectories(Path.of("target"));
        root = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "restore-pg-test-");
        data = root.resolve("data");
        int port;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        config = new DbConfig("127.0.0.1", port, "restore_test", "", "postgres", false);
        runTool("initdb", "-D", data.toString(), "-U", "restore_test", "--auth=trust", "--encoding=UTF8", "--no-locale");
        initialized = true;
        runTool("pg_ctl", "-D", data.toString(), "-l", root.resolve("server.log").toString(),
                "-o", "-h 127.0.0.1 -p " + port + " -F", "-w", "start");
    }

    @AfterAll
    static void stopIsolatedServer() throws Exception {
        if (initialized && Files.exists(data.resolve("postmaster.pid"))) {
            runTool("pg_ctl", "-D", data.toString(), "-m", "immediate", "-w", "stop");
        }
    }

    @Test
    void actualDumpRestoresSchemaAndRows() throws Exception {
        try (Connection connection = connect(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE round_trip(id integer PRIMARY KEY, label text)");
            statement.execute("INSERT INTO round_trip VALUES (1, 'hello'), (2, 'café')");
            PostgresService service = new PostgresService();
            BackupResult backup = service.backup(config, root.toString(), CompressionStrategy.GZIP);
            assertEquals(BackupResult.Status.SUCCESS, backup.getStatus(), backup.getErrorMessage());
            statement.execute("DROP TABLE round_trip");
            RestoreResult restored = service.restore(config, backup.getOutputPath());
            assertEquals(RestoreResult.Status.SUCCESS, restored.getStatus(), restored.getErrorMessage());
            try (var rows = statement.executeQuery("SELECT id, label FROM round_trip ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
                assertEquals("hello", rows.getString(2));
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
                assertEquals("café", rows.getString(2));
                assertFalse(rows.next());
            }
            statement.execute("DROP TABLE round_trip");
        }
    }

    @Test
    void sqlErrorReturnsFailureAndRollsBackEarlierStatements() throws Exception {
        Path archive = archive("invalid.sql.gz",
                "CREATE TABLE must_rollback(id int); INSERT INTO must_rollback VALUES (1); INVALID SQL;\n"
                        + "SELECT 1;\n".repeat(100_000)); // larger than the pipe: client exits during streaming
        RestoreResult result = new PostgresService().restore(config, archive.toString());
        assertEquals(RestoreResult.Status.FAILED, result.getStatus());
        assertEquals(3, result.getExitCode());
        assertTableAbsent("must_rollback");
    }

    @Test
    void corruptedTrailerCannotApplyOtherwiseValidSql() throws Exception {
        Path archive = archive("corrupt.sql.gz", "CREATE TABLE must_not_exist(id int);");
        byte[] bytes = Files.readAllBytes(archive);
        bytes[bytes.length - 8] ^= 1;
        Files.write(archive, bytes);
        RestoreResult result = new PostgresService().restore(config, archive.toString());
        assertEquals(RestoreResult.Status.ABORTED, result.getStatus());
        assertTableAbsent("must_not_exist");
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + config.getPort() + "/postgres",
                config.getUser(), config.getPassword());
    }

    private static void assertTableAbsent(String table) throws Exception {
        try (Connection connection = connect();
             var statement = connection.prepareStatement("SELECT to_regclass(?)")) {
            statement.setString(1, table);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertNull(rows.getString(1));
            }
        }
    }

    private static Path archive(String name, String sql) throws Exception {
        Path path = root.resolve(name);
        try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(path))) {
            output.write(sql.getBytes(StandardCharsets.UTF_8));
        }
        return path;
    }

    private static void runTool(String tool, String... arguments) throws Exception {
        String executable = tool + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getenv("RESTORE_TEST_PG_BIN"), executable).toString());
        command.addAll(List.of(arguments));
        Path log = root.resolve(tool + "-" + System.nanoTime() + ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), tool + " timed out; see " + log);
            assertEquals(0, process.exitValue(), () -> {
                try { return Files.readString(log); } catch (Exception e) { return log.toString(); }
            });
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }
}
