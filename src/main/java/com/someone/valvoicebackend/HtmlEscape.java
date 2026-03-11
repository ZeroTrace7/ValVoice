package com.someone.valvoicebackend;

/**
 * Minimal HTML entity un-escape (and now escape) utility.
 *
 * PHASE 1 SECURITY HARDENING (VN-Parity):
 * ════════════════════════════════════════════════════════════════════════════════
 * HTML UNESCAPING HAPPENS EXACTLY ONCE - This is the SINGLE point of unescaping.
 *
 * Call sites (AUTHORITATIVE LIST):
 * - Message.java constructor: body content is unescaped ONCE during parsing
 *
 * IMPORTANT: Do NOT call unescapeHtml() multiple times on the same content.
 * Double-unescaping would convert &amp;lt; → &lt; → <, which is incorrect.
 *
 * The VN-parity contract is:
 * 1. Raw body extracted from XML (still escaped, e.g., "&lt;hello&gt;")
 * 2. HtmlEscape.unescapeHtml() called ONCE → produces "<hello>"
 * 3. Content flows through ChatDataHandler → VoiceGenerator → TTS
 * 4. NO additional unescaping anywhere in the pipeline
 *
 * PERFORMANCE: Single-pass StringBuilder decoder. No intermediate String
 * allocations. Handles named entities (&amp; &lt; &gt; &quot; &#39; &apos;)
 * and numeric entities (&#65; &#x41;) in one pass.
 * ════════════════════════════════════════════════════════════════════════════════
 */
public final class HtmlEscape {
    private HtmlEscape() {}

    public static String unescapeHtml(String input) {
        if (input == null || input.isEmpty()) return input;

        // Fast path: if no '&' present, no entities to decode
        if (input.indexOf('&') == -1) {
            return input;
        }

        StringBuilder result = new StringBuilder(input.length());

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '&') {
                // Named entities (HTML4 + HTML5)
                if (input.startsWith("&amp;", i)) {
                    result.append('&');
                    i += 4;
                    continue;
                }
                if (input.startsWith("&lt;", i)) {
                    result.append('<');
                    i += 3;
                    continue;
                }
                if (input.startsWith("&gt;", i)) {
                    result.append('>');
                    i += 3;
                    continue;
                }
                if (input.startsWith("&quot;", i)) {
                    result.append('"');
                    i += 5;
                    continue;
                }
                if (input.startsWith("&#39;", i)) {
                    result.append('\'');
                    i += 4;
                    continue;
                }
                if (input.startsWith("&apos;", i)) {
                    result.append('\'');
                    i += 5;
                    continue;
                }

                // Numeric entities: &#digits; or &#xhex;
                if (i + 2 < input.length() && input.charAt(i + 1) == '#') {
                    int semicolon = input.indexOf(';', i + 2);
                    if (semicolon > 0 && semicolon - i <= 10) { // reasonable max entity length
                        String inner = input.substring(i + 2, semicolon);
                        int codePoint = -1;
                        try {
                            if (inner.length() > 0 && (inner.charAt(0) == 'x' || inner.charAt(0) == 'X')) {
                                codePoint = Integer.parseInt(inner.substring(1), 16);
                            } else {
                                codePoint = Integer.parseInt(inner, 10);
                            }
                        } catch (NumberFormatException ignored) {
                        }
                        if (codePoint >= 0 && Character.isValidCodePoint(codePoint)) {
                            result.appendCodePoint(codePoint);
                            i = semicolon; // loop increment handles +1
                            continue;
                        }
                    }
                }
            }

            result.append(c);
        }

        return result.toString();
    }

    public static String escapeHtml(String input) {
        if (input == null || input.isEmpty()) return input;
        StringBuilder sb = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c); break;
            }
        }
        return sb.toString();
    }
}
