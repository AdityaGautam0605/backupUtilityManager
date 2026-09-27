package com.backuputil.util;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiClientTest {
    static String response(String text) {
        return new Document("status", "completed").append("output", java.util.List.of(
                new Document("type", "message").append("role", "assistant").append("status", "completed")
                        .append("content", java.util.List.of(new Document("type", "output_text").append("text", text)))))
                .toJson();
    }

    @Test
    void sendsResponsesRequestWithBearerAuthAndEscapedInput() {
        String prompt = "Line one\n\"quoted\" \\ café";
        OpenAiClient client = new OpenAiClient(" test-key ", "gpt-4.1-mini", false, request -> {
            assertEquals("https://api.openai.com/v1/responses", request.uri().toString());
            assertEquals("Bearer test-key", request.headers().firstValue("Authorization").orElseThrow());
            assertEquals("POST", request.method());
            CompletableFuture<String> body = new CompletableFuture<>();
            request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
                private final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                public void onNext(ByteBuffer buffer) {
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    output.writeBytes(bytes);
                }
                public void onError(Throwable error) { body.completeExceptionally(error); }
                public void onComplete() { body.complete(output.toString(StandardCharsets.UTF_8)); }
            });
            Document json = Document.parse(body.get());
            assertEquals(prompt, json.getString("input"));
            assertEquals("gpt-4.1-mini", json.getString("model"));
            assertEquals(false, json.getBoolean("store"));
            assertEquals(1024, json.getInteger("max_output_tokens"));
            return new OpenAiClient.Reply(200, response("Backup healthy"));
        });
        assertEquals("Backup healthy", client.generate(prompt));
    }

    @Test
    void missingKeyAndMockModeMakeNoRequests() {
        OpenAiClient.Transport forbidden = request -> { fail("Unexpected API request"); return null; };
        assertNull(new OpenAiClient(null, "model", false, forbidden).generate("test"));
        assertNull(new OpenAiClient("  ", "model", false, forbidden).generate("test"));
        assertNull(new OpenAiClient("key", "model", true, forbidden).generate("test"));
    }

    @Test
    void httpAndTransportFailuresFallBack() {
        for (int status : new int[]{401, 429, 500}) {
            assertNull(new OpenAiClient("key", "model", false,
                    request -> new OpenAiClient.Reply(status, "error")).generate("test"));
        }
        assertNull(new OpenAiClient("key", "model", false,
                request -> { throw new java.io.IOException("offline"); }).generate("test"));
    }

    @Test
    void interruptionIsPreserved() {
        try {
            assertNull(new OpenAiClient("key", "model", false,
                    request -> { throw new InterruptedException(); }).generate("test"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test
    void parsesOnlyCompletedAssistantText() {
        assertEquals("hello\n\"café\"", LlmResponse.extractOpenAiText(response("hello\n\"café\"")));
        String multiple = """
                {"status":"completed", "text":"metadata must be ignored", "output":[
                  {"type":"reasoning", "text":"ignore reasoning"},
                  {"type":"message","role":"assistant","status":"completed","content":[
                    {"type":"output_text","text":"first"}, {"type":"output_text","text":"second"}]}]}
                """;
        assertEquals("first\nsecond", LlmResponse.extractOpenAiText(multiple));
    }

    @Test
    void rejectsIncompleteRefusedAndMalformedResponses() {
        assertNull(LlmResponse.extractOpenAiText(response("partial").replace("completed", "incomplete")));
        assertNull(LlmResponse.extractOpenAiText(response("no").replace("output_text", "refusal")));
        assertNull(LlmResponse.extractOpenAiText("{\"status\":\"completed\",\"output\":[]}"));
        assertNull(LlmResponse.extractOpenAiText("{bad json"));
        assertNull(LlmResponse.extractOpenAiText(null));
    }
}
