package com.backuputil.util;

/**
 * Minimal, dependency-free reader for Anthropic Messages API responses.
 *
 * The response shape is:
 * <pre>
 * { ..., "content":[{"type":"text","text":"the answer"}], "stop_reason":"end_turn", "usage":{...} }
 * </pre>
 *
 * Earlier code located the text value with {@code indexOf("\"text\":")} and then
 * {@code lastIndexOf("\"")} — but because real responses carry fields *after* the
 * text block, that grabbed everything up to the last quote in {@code usage}/{@code stop_reason}.
 * This walks the string value properly, honouring JSON escape sequences and stopping
 * at the first unescaped closing quote.
 */
public final class ClaudeResponse {

    private static final String TEXT_KEY = "\"text\":";

    private ClaudeResponse() {
    }

    /**
     * Extracts the first {@code "text"} content block, or {@code null} if none is present.
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
