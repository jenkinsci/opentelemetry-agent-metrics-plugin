package io.jenkins.plugins.onmonit;

/**
 * Quotes values for the OpenTelemetry collector configuration. Exposed to config templates as {@code yaml}, used as
 * {@code [(${yaml.quote(value)})]}.
 *
 * <p>Thymeleaf's escaped inlining {@code [[...]]} HTML-escapes in TEXT mode, which corrupts values containing
 * {@code '}, {@code "} or {@code &}. Use unescaped inlining with this helper instead.
 */
public final class YamlQuoting {

    /**
     * Returns {@code value} as a single-line, double-quoted YAML scalar. {@code null} becomes {@code ""}. Every
     * {@code $} is doubled, because the collector expands {@code ${...}} references in its config and reads
     * {@code $$} as a literal {@code $}.
     */
    public String quote(Object value) {
        String s = value == null ? "" : value.toString();
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '$' -> sb.append("$$");
                default -> {
                    if (needsUnicodeEscape(c)) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** C0/C1 controls, DEL, line/paragraph separators and the BOM, which YAML treats as non-printable or line breaks. */
    private static boolean needsUnicodeEscape(char c) {
        return c < 0x20 || (c >= 0x7f && c <= 0x9f) || c == 0x2028 || c == 0x2029 || c == 0xfeff;
    }
}
