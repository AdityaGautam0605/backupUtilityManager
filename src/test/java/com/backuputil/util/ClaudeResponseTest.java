package com.backuputil.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the escape-aware Anthropic response parser.
 *
 * The key regression these guard against: earlier code used indexOf("text") ... lastIndexOf('"'),
 * which on a real response captured everything up to the last quote in the trailing
 * "stop_reason"/"usage" fields. extractText must stop at the first UNESCAPED closing quote.
 */
class ClaudeResponseTest {

    @Test
    void extractsTextAndIgnoresTrailingFields() {
        String body = "{\"content\":[{\"type\":\"text\",\"text\":\"hello world\"}]," +
                "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":5,\"output_tokens\":9}}";
        assertEquals("hello world", ClaudeResponse.extractText(body));
    }

    @Test
    void decodesEscapeSequences() {
        // Raw JSON value is:  line1\nline2 \"quoted\"
        String body = "{\"content\":[{\"type\":\"text\",\"text\":\"line1\\nline2 \\\"quoted\\\"\"}]}";
        assertEquals("line1\nline2 \"quoted\"", ClaudeResponse.extractText(body));
    }

    @Test
    void decodesUnicodeEscape() {
        String body = "{\"text\":\"caf\\u00e9\"}";
        assertEquals("café", ClaudeResponse.extractText(body));
    }

    @Test
    void returnsNullWhenNoTextBlock() {
        assertNull(ClaudeResponse.extractText("{\"error\":{\"message\":\"bad request\"}}"));
    }

    @Test
    void returnsNullOnNullInput() {
        assertNull(ClaudeResponse.extractText(null));
    }
}
