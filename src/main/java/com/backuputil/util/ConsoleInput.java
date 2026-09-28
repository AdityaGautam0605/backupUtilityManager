package com.backuputil.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;

/** Reads one answer without buffering input needed by a later workflow step. */
public final class ConsoleInput {
    private ConsoleInput() {}

    public static String readLine(String prompt) throws IOException {
        if (System.console() != null) return System.console().readLine("%s", prompt);
        System.out.print(prompt);
        System.out.flush();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = System.in.read()) != -1 && value != '\n') {
            if (value != '\r') bytes.write(value);
        }
        return value == -1 && bytes.size() == 0 ? null : bytes.toString(Charset.defaultCharset());
    }

    public static String readPassword() throws IOException {
        if (System.console() != null) {
            char[] chars = System.console().readPassword("Enter database password: ");
            if (chars == null) return null;
            try { return new String(chars); }
            finally { Arrays.fill(chars, '\0'); }
        }
        System.out.println("[Input] This console cannot mask password input; typed characters may be visible.");
        return readLine("Enter database password: ");
    }
}
