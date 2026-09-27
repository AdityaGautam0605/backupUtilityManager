package com.backuputil.config;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {
    @Test
    void readsOpenAiSettingsAndTrimsValues() {
        AppConfig config = new AppConfig(Map.of("OPENAI_API_KEY", " key ", "OPENAI_MODEL", " my-model ", "MOCK_AI", " true "));
        assertEquals("key", config.getApiKey());
        assertEquals("my-model", config.getAiModel());
        assertTrue(config.isMockAi());
        assertTrue(config.isAiEnabled());
    }

    @Test
    void legacyKeyDoesNotEnableOpenAiAndDefaultModelIsUsed() {
        AppConfig config = new AppConfig(Map.of("GEMINI_API_KEY", "old-key"));
        assertFalse(config.isAiEnabled());
        assertNull(config.getApiKey());
        assertEquals("gpt-4.1-mini", config.getAiModel());
    }
}
