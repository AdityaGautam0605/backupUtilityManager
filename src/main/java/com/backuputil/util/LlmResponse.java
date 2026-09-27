package com.backuputil.util;

/**
 * Minimal, dependency-free extractor for the first JSON {@code "text"} value in an LLM
 * response body.
 *
 * Works for both response shapes this project has used:
 * <ul>
 *   <li>Anthropic: {@code {"content":[{"type":"text","text":"..."}], ...}}</li>
 *   <li>Google Gemini: {@code {"candidates":[{"content":{"parts":[{"text":"..."}]}}], ...}}</li>
 * </ul>
 * In both, the model's output is the first {@code "text":} field in the payload.
 *
 * Walks the string value honouring JSON escape sequences and stops at the first unescaped
 * closing quote — a naive {@code lastIndexOf('"')} would over-capture trailing metadata
 * ({@code usageMetadata}, {@code finishReason}, {@code stop_reason}, ...).
 */
public final class LlmResponse {

    private static final String TEXT_KEY = "\"text\":";

    private LlmResponse() {
    }

    /** Extract only completed assistant output from the Responses API, ignoring metadata/tools. */
    public static String extractOpenAiText(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            // Reuse the BSON library's JSON parser already supplied by the MongoDB driver.
            org.bson.Document response = org.bson.Document.parse(body);
            if (!"completed".equals(response.getString("status")) || response.get("error") != null) return null;
            Object output = response.get("output");
            if (!(output instanceof java.util.List<?> items)) return null;
            StringBuilder text = new StringBuilder();
            for (Object item : items) {
                if (!(item instanceof org.bson.Document message)
                        || !"message".equals(message.getString("type"))
                        || !"assistant".equals(message.getString("role"))) continue;
                if (!"completed".equals(message.getString("status"))) return null;
                if (!(message.get("content") instanceof java.util.List<?> content)) continue;
                for (Object part : content) {
                    if (!(part instanceof org.bson.Document block)) continue;
                    if ("refusal".equals(block.getString("type"))) return null;
                    if ("output_text".equals(block.getString("type")) && block.get("text") instanceof String value) {
                        if (!text.isEmpty()) text.append('\n');
                        text.append(value);
                    }
                }
            }
            return text.toString().isBlank() ? null : text.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Extracts the first {@code "text"} value, or {@code null} if none is present.
     */
    public static String extractText(String responseBody) {
        if (responseBody == null) return null;

        int key = responseBody.indexOf(TEXT_KEY);
        if (key == -1) return null;

        // Advance to the opening quote of the value, skipping any whitespace after the colon.
        int i = key + TEXT_KEY.length();
        while (i < responseBody.length() && Character.isWhitespace(responseBody.charAt(i))) {
            i++;
        }
        if (i >= responseBody.length() || responseBody.charAt(i) != '"') {
            return null;
        }
        i++; // step past the opening quote

        StringBuilder sb = new StringBuilder();
        while (i < responseBody.length()) {
            char c = responseBody.charAt(i);

            if (c == '"') {
                // First unescaped quote terminates the value.
                return sb.toString();
            }

            if (c == '\\' && i + 1 < responseBody.length()) {
                char next = responseBody.charAt(i + 1);
                switch (next) {
                    case 'n': sb.append('\n'); i += 2; break;
                    case 't': sb.append('\t'); i += 2; break;
                    case 'r': sb.append('\r'); i += 2; break;
                    case 'b': sb.append('\b'); i += 2; break;
                    case 'f': sb.append('\f'); i += 2; break;
                    case '"': sb.append('"'); i += 2; break;
                    case '\\': sb.append('\\'); i += 2; break;
                    case '/': sb.append('/'); i += 2; break;
                    case 'u':
                        if (i + 6 <= responseBody.length()) {
                            try {
                                int code = Integer.parseInt(responseBody.substring(i + 2, i + 6), 16);
                                sb.append((char) code);
                                i += 6;
                            } catch (NumberFormatException e) {
                                sb.append(next);
                                i += 2;
                            }
                        } else {
                            sb.append(next);
                            i += 2;
                        }
                        break;
                    default:
                        sb.append(next);
                        i += 2;
                        break;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        // Ran off the end without a closing quote — return what we have.
        return sb.toString();
    }
}
