package com.backuputil.util;

/**
 * Minimal, dependency-free JSON string escaping.
 *
 * The AI callers build request bodies by hand and embed user input / native tool stderr
 * into a JSON string. Those sources can contain quotes, backslashes, newlines, tabs, and
 * other control characters (anything below U+0020) — all of which are illegal raw inside a
 * JSON string and would produce a malformed body (HTTP 400) if not escaped. This escaper
 * follows RFC 8259: the seven short escapes plus \\uXXXX for every remaining control char.
 */
public final class JsonStrings {

    private JsonStrings() {
    }

    /** Escapes {@code s} for safe embedding inside a JSON string literal. Null becomes "". */
    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
