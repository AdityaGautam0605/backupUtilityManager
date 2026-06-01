package com.backuputil.config;

public class AppConfig {
    private static final String AI_KEY_ENV = "KEY";
    private static final String MOCK_AI_ENV = "MOCK_AI";

    private final String aiApiKey;
    private final boolean mockAi;

    private AppConfig(){
        this.aiApiKey = System.getenv(AI_KEY_ENV);
        this.mockAi = "true".equalsIgnoreCase(System.getenv(MOCK_AI_ENV));
    }

    // Singleton - one config instance for the whole app
    private static final AppConfig INSTANCE  = new AppConfig();
    public static AppConfig getInstance() {return INSTANCE;}

    public String getAnthropicApiKey(){return aiApiKey;}
    public boolean isMockAi(){return mockAi;}

    public boolean isAiEnabled(){
        return mockAi || (aiApiKey != null && !aiApiKey.isBlank());
    }

    public void printStatus(){
        if (mockAi) {
            System.out.println("[AI] Mock mode active — no API calls will be made");
        } else if (isAiEnabled()) {
            System.out.println("[AI] Anthropic API key loaded successfully");
        } else {
            System.out.println("[AI] No API key found — AI features disabled. Set ANTHROPIC_API_KEY to enable.");
        }

    }
}
