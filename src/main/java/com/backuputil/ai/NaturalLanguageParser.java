package com.backuputil.ai;

import com.backuputil.config.AppConfig;
import com.backuputil.model.ParsedIntent;
import com.backuputil.util.GeminiClient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NaturalLanguageParser {

    private final AppConfig config;
    private final GeminiClient gemini;

    public NaturalLanguageParser() {
        this.config = AppConfig.getInstance();
        this.gemini = new GeminiClient();
    }

    public ParsedIntent parse (String input){
        if(config.isMockAi()){
            return ruleBasedParse(input);
        }
        try{
            return aiParse(input);
        }catch (Exception e){
            System.err.println ("[NL Parser] AI parsing failed (" + e.getMessage()
                    + ") — falling back to rule-based extraction");
            return ruleBasedParse(input);
        }
    }

    // rule based fallback, used in mock mode or if the API call fails
    private ParsedIntent ruleBasedParse(String input){
        ParsedIntent intent = new ParsedIntent();
        String lower = input.toLowerCase();

        if (lower.contains("postgres")) intent.setDbType("postgres");
        else if (lower.contains("mysql")) intent.setDbType("mysql");
        else if (lower.contains("mongo")) intent.setDbType("mongo");

        Matcher hostMatcher = Pattern.compile("\\b(localhost|\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|[a-zA-Z0-9-]+\\.[a-zA-Z]{2,})\\b"
        ).matcher(lower);
        if (hostMatcher.find()) intent.setHost(hostMatcher.group(1));

        Matcher portMatcher = Pattern.compile("port\\s+(\\d+)|:(\\d{2,5})\\b").matcher(lower);
        if (portMatcher.find()){
            String p = portMatcher.group(1) != null ? portMatcher.group(1) : portMatcher.group(2);
            intent.setPort(Integer.parseInt(p));
        }

        Matcher nameMatcher = Pattern.compile("(?:called|named)\\s+([a-zA-Z0-9_]+)").matcher(lower);
        if (nameMatcher.find()){
            intent.setDbName(nameMatcher.group(1));
        }else{

            Matcher dbMatcher = Pattern.compile("\\b(?:database|db)\\s+([a-zA-Z0-9_]+)").matcher(lower);
            if (dbMatcher.find() && !dbMatcher.group(1).matches("called|named|on|with|for")){
                intent.setDbName(dbMatcher.group(1));
            }
        }

        Matcher userMatcher = Pattern.compile("\\buser\\s+([a-zA-Z0-9_]+)").matcher(lower);
        if (userMatcher.find()) intent.setUser(userMatcher.group(1));

        if (lower.contains("mock") || lower.contains("test mode")) intent.setMock(true);

        return intent;
    }

    private ParsedIntent aiParse(String input) throws Exception{
        String systemInstruction = "Extract database backup parameters from the user's sentence. " +
                "Respond with ONLY a raw JSON object, no markdown, no explanation. " +
                "Fields: dbType (postgres/mysql/mongo or null), host (or null), " +
                "port (integer or null), user (or null), dbName (or null), mock (boolean). " +
                "Never extract passwords under any circumstance, even if mentioned. " +
                "Sentence: " + input;

        // The model returns a JSON object as plain text; extract the fields from it.
        String jsonText = gemini.generate(systemInstruction);
        if (jsonText == null){
            throw new RuntimeException("Gemini returned no usable response");
        }
        return parseJsonResponse(jsonText);
    }

    private ParsedIntent parseJsonResponse (String json){
        ParsedIntent intent = new ParsedIntent();
        intent.setDbType(extractStringField(json, "dbType"));
        intent.setHost(extractStringField(json, "host"));
        intent.setUser(extractStringField(json, "user"));
        intent.setDbName(extractStringField(json, "dbName"));

        String portStr = extractStringField(json, "port");
        if (portStr != null && portStr.matches("\\d+")) {
            intent.setPort(Integer.parseInt(portStr));
        }

        intent.setMock(json.contains("\"mock\": true") || json.contains("\"mock\":true"));
        return intent;
    }

    private String extractStringField(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"?([^\",}]+)\"?").matcher(json);
        if (m.find()) {
            String value = m.group(1).trim();
            return (value.equalsIgnoreCase("null") || value.isEmpty()) ? null : value;
        }
        return null;
    }
}
