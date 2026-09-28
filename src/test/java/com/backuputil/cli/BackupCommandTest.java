package com.backuputil.cli;

import com.backuputil.config.DbConfig;
import com.backuputil.model.*;
import com.backuputil.service.DatabaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;
import java.io.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class BackupCommandTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void promptsOnceWithOrWithoutPasswordFlag(boolean flag) throws Exception {
        AtomicReference<DbConfig> captured = new AtomicReference<>();
        String[] args = flag ? new String[]{"-t", "postgres", "-u", "postgres", "-d", "shop", "-P"}
                : new String[]{"-t", "postgres", "-u", "postgres", "-d", "shop"};
        String output = run(args, " secret with spaces \nZSTD\nyes\n", captured);
        assertEquals(" secret with spaces ", captured.get().getPassword());
        assertEquals(1, output.split("Enter database password:", -1).length - 1);
        assertFalse(output.contains("secret with spaces"));
    }

    @Test
    void sequentialPromptsPreserveLaterAnswers() throws Exception {
        AtomicReference<DbConfig> captured = new AtomicReference<>();
        run(new String[]{}, "postgres\r\npostgres\r\nshop\r\nsecret\r\nZSTD\r\nyes\r\n", captured);
        assertEquals("shop", captured.get().getDbName());
        assertEquals("secret", captured.get().getPassword());
    }

    @Test
    void eofCancelsBeforeConnectionButEmptyPasswordIsAllowed() throws Exception {
        AtomicReference<DbConfig> captured = new AtomicReference<>();
        String[] args = {"-t", "postgres", "-u", "postgres", "-d", "shop"};
        run(args, "", captured);
        assertNull(captured.get());
        run(args, "\nZSTD\nyes\n", captured);
        assertEquals("", captured.get().getPassword());
    }

    private String run(String[] args, String input, AtomicReference<DbConfig> captured) throws Exception {
        InputStream oldIn = System.in;
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream(input.getBytes(java.nio.charset.Charset.defaultCharset())));
            System.setOut(new PrintStream(output));
            System.setErr(new PrintStream(output));
            BackupCommand command = new BackupCommand() {
                @Override DatabaseService createService(String type) {
                    return new DatabaseService() {
                        public boolean testConnection(DbConfig config) { captured.set(config); return false; }
                        public long estimateSizeBytes(DbConfig config) { throw new AssertionError(); }
                        public BackupResult backup(DbConfig c, String dir, CompressionStrategy s) { throw new AssertionError(); }
                        public RestoreResult restore(DbConfig c, String path) { throw new AssertionError(); }
                    };
                }
            };
            assertEquals(1, new CommandLine(command).execute(args));
            if (input.endsWith("yes\n") || input.endsWith("yes\r\n")) {
                assertEquals("ZSTD", com.backuputil.util.ConsoleInput.readLine(""));
                assertEquals("yes", com.backuputil.util.ConsoleInput.readLine(""));
            }
            return output.toString();
        } finally {
            System.setIn(oldIn);
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }
}
