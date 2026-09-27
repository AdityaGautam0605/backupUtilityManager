package com.backuputil.config;

public class AppConfig {
    private static final String AI_KEY_ENV = "OPENAI_API_KEY";
    private static final String MOCK_AI_ENV = "MOCK_AI";

    private final String aiApiKey;
    private final boolean mockAi;
    private final String aiModel;

    private AppConfig(){
        this(System.getenv());
    }

    AppConfig(java.util.Map<String, String> environment) {
        String key = environment.get(AI_KEY_ENV);
        this.aiApiKey = key == null ? null : key.trim();
        this.mockAi = "true".equalsIgnoreCase(environment.getOrDefault(MOCK_AI_ENV, "false").trim());
        String model = environment.getOrDefault("OPENAI_MODEL", "").trim();
        this.aiModel = model.isEmpty() ? "gpt-4.1-mini" : model;
    }

    // Singleton - one config instance for the whole app
    private static final AppConfig INSTANCE  = new AppConfig();
    public static AppConfig getInstance() {return INSTANCE;}

    public String getApiKey(){return aiApiKey;}
    public String getAiModel(){return aiModel;}
    public boolean isMockAi(){return mockAi;}

    public boolean isAiEnabled(){
        return mockAi || (aiApiKey != null && !aiApiKey.isBlank());
    }

    public void printStatus(){
        if (mockAi) {
            System.out.println("[AI] Mock mode active — no API calls will be made");
        } else if (isAiEnabled()) {
            System.out.println("[AI] OpenAI API key loaded; model: " + aiModel);
        } else {
            System.out.println("[AI] No API key found — AI features disabled. Set OPENAI_API_KEY to enable.");
        }

    }
}
