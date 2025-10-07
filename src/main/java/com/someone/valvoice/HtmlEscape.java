package com.someone.valvoice;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal HTML entity un-escape (and now escape) utility.
 */
public final class HtmlEscape {
    private HtmlEscape() {}

    private static final Pattern DEC_ENTITY = Pattern.compile("&#(\\d+);");
    private static final Pattern HEX_ENTITY = Pattern.compile("&#x([0-9a-fA-F]+);");

    public static String unescapeHtml(String input) {
        if (input == null || input.isEmpty()) return input;
        String s = input;
        // Basic named entities
        s = s.replace("&amp;", "&");
        s = s.replace("&lt;", "<");
        s = s.replace("&gt;", ">");
        s = s.replace("&quot;", "\"");
        s = s.replace("&#39;", "'");

        // Numeric decimal entities (e.g., &#65;)
        s = replaceNumericEntities(s, DEC_ENTITY, 10);
        // Numeric hex entities (e.g., &#x41;)
        s = replaceNumericEntities(s, HEX_ENTITY, 16);
        return s;
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

    private static String replaceNumericEntities(String input, Pattern pattern, int radix) {
        Matcher m = pattern.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String replacement;
            try {
                int codePoint = Integer.parseInt(m.group(1), radix);
                if (!Character.isValidCodePoint(codePoint)) {
                    replacement = m.group();
                } else {
                    replacement = new String(Character.toChars(codePoint));
                }
            } catch (NumberFormatException e) {
                replacement = m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
