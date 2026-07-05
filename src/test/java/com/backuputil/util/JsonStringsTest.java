package com.backuputil.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonStringsTest {

    @Test
    void escapesQuotesAndBackslashes() {
        assertEquals("a\\\"b\\\\c", JsonStrings.escape("a\"b\\c"));
    }

    @Test
    void escapesCommonControlChars() {
        assertEquals("line1\\nline2\\ttab", JsonStrings.escape("line1\nline2\ttab"));
    }

    @Test
    void escapesArbitraryControlCharAsUnicode() {
        // U+0001 has no short escape, so it must be emitted as a \\uXXXX sequence
        // or the JSON request body would be invalid. Build it programmatically to
        // keep a raw control character out of the source file.
        String input = "x" + ((char) 0x01) + "y";
        assertEquals("x\\u0001y", JsonStrings.escape(input));
    }

    @Test
    void nullBecomesEmptyString() {
        assertEquals("", JsonStrings.escape(null));
    }
}
