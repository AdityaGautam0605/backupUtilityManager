package com.backuputil.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the provider-neutral response text extractor.
 *
 * The key regression these guard against: a naive indexOf("text") ... lastIndexOf('"')
 * over-captures trailing metadata (usageMetadata / finishReason / stop_reason). extractText
 * must stop at the first UNESCAPED closing quote — and work for both Gemini and Anthropic shapes.
 */
class LlmResponseTest {

    @Test
    void extractsFromGeminiResponseShape() {
        String body = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"backup looks healthy\"}]," +
                "\"role\":\"model\"},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"totalTokenCount\":42}}";
        assertEquals("backup looks healthy", LlmResponse.extractText(body));
    }

    @Test
    void extractsFromAnthropicShapeAndIgnoresTrailingFields() {
        String body = "{\"content\":[{\"type\":\"text\",\"text\":\"hello world\"}]," +
                "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":5,\"output_tokens\":9}}";
        assertEquals("hello world", LlmResponse.extractText(body));
    }

    @Test
    void decodesEscapeSequences() {
        // Raw JSON value is:  line1\nline2 \"quoted\"
        String body = "{\"text\":\"line1\\nline2 \\\"quoted\\\"\"}";
        assertEquals("line1\nline2 \"quoted\"", LlmResponse.extractText(body));
    }

    @Test
    void decodesUnicodeEscape() {
        String body = "{\"text\":\"caf\\u00e9\"}";
        assertEquals("café", LlmResponse.extractText(body));
    }

    @Test
    void returnsNullWhenNoTextBlock() {
        assertNull(LlmResponse.extractText("{\"error\":{\"message\":\"bad request\"}}"));
    }

    @Test
    void returnsNullOnNullInput() {
        assertNull(LlmResponse.extractText(null));
    }
}
