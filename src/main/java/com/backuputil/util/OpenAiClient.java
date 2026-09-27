package com.backuputil.util;

import com.backuputil.config.AppConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** OpenAI Responses API client. Failures return null so callers retain offline fallbacks. */
public final class OpenAiClient {
    private final String apiKey;
    private final String model;
    private final boolean mock;
    private final Transport transport;

    record Reply(int status, String body) {}
    @FunctionalInterface
    interface Transport { Reply send(HttpRequest request) throws Exception; }

    public OpenAiClient() {
        AppConfig config = AppConfig.getInstance();
        apiKey = config.getApiKey();
        model = config.getAiModel();
        mock = config.isMockAi();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        transport = request -> {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        };
    }

    OpenAiClient(String apiKey, String model, boolean mock, Transport transport) {
        this.apiKey = apiKey;
        this.model = model;
        this.mock = mock;
        this.transport = transport;
    }

    public String generate(String prompt) {
        if (mock || apiKey == null || apiKey.isBlank()) return null;
        try {
            String body = "{\"model\":\"" + JsonStrings.escape(model)
                    + "\",\"input\":\"" + JsonStrings.escape(prompt)
                    + "\",\"max_output_tokens\":1024,\"store\":false}";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/responses"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            Reply response = transport.send(request);
            if (response.status() != 200) {
                System.err.println("[AI] OpenAI API call failed with status: " + response.status());
                return null;
            }
            return LlmResponse.extractOpenAiText(response.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // Do not log request headers, prompts, or response bodies that may contain secrets.
            System.err.println("[AI] OpenAI request failed (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }
}
