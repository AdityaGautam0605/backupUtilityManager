package com.backuputil.util;

import com.backuputil.config.AppConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal client for Google's Gemini API (Generative Language API, free tier).
 *
 * Replaces the previous Anthropic/Claude integration. One text prompt in, the model's
 * reply text out — or {@code null} on any failure (missing key, non-200, parse error, timeout)
 * so callers can fall back to their rule-based path. The API key is read from the
 * {@code GEMINI_API_KEY} environment variable via {@link AppConfig}.
 *
 * Get a free key at https://aistudio.google.com/apikey
 */
public final class GeminiClient {

    // Free-tier flash model. If a request 404s on the model name, switch to "gemini-2.0-flash".
    private static final String MODEL = "gemini-2.5-flash";
    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";

    private final AppConfig config;
    private final HttpClient httpClient;

    public GeminiClient() {
        this.config = AppConfig.getInstance();
        // Connect timeout so a hung/unreachable API can't freeze the whole CLI.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * Sends a single text prompt to Gemini and returns the model's reply text, or
     * {@code null} if the key is missing, the request fails, or no text comes back.
     */
    public String generate(String prompt) {
        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        try {
            String requestBody = String.format("""
                    {
                        "contents": [{"parts": [{"text": "%s"}]}],
                        "generationConfig": {"maxOutputTokens": 1024}
                    }
                    """, JsonStrings.escape(prompt));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)   // key in a header, never in the URL
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("[AI] Gemini API call failed with status: " + response.statusCode());
                return null;
            }
            // Gemini's reply is at candidates[0].content.parts[0].text — the first "text":
            // value in the payload, which the shared extractor returns.
            return LlmResponse.extractText(response.body());
        } catch (Exception e) {
            System.err.println("[AI] Gemini call failed: " + e.getMessage());
            return null;
        }
    }
}
