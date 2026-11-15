package io.jenkins.plugins.onmonit;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Parses {@code OTEL_EXPORTER_OTLP_HEADERS} as defined by the
 * <a href="https://opentelemetry.io/docs/specs/otel/protocol/exporter/#specifying-headers-via-environment-variables">OTLP
 * exporter spec</a>: comma-separated {@code key=value} pairs, keys and values trimmed, values percent-decoded.
 *
 * <p>A literal {@code +} is kept as is (only {@code %XX} sequences are decoded), so base64 credentials survive.
 * Malformed members are skipped with a warning. Header values are never logged, since they usually hold credentials.
 */
final class OtlpHeaders {

    private static final Logger LOGGER = Logger.getLogger(OtlpHeaders.class.getName());

    private static final String INDENT = "      ";

    private OtlpHeaders() {}

    /** Returns the headers in input order; a later duplicate key replaces an earlier one. Never throws. */
    static Map<String, String> parse(@CheckForNull String raw) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return headers;
        }
        String[] members = raw.split(",", -1);
        for (int i = 0; i < members.length; i++) {
            String member = members[i].trim();
            if (member.isEmpty()) {
                continue;
            }
            int eq = member.indexOf('=');
            String key = eq < 0 ? "" : member.substring(0, eq).trim();
            if (key.isEmpty()) {
                LOGGER.log(Level.WARNING, "Ignoring OTEL_EXPORTER_OTLP_HEADERS member #{0}: expected key=value", i + 1);
                continue;
            }
            String value = decode(key, member.substring(eq + 1).trim());
            if (hasControlCharacter(key) || hasControlCharacter(value)) {
                LOGGER.log(Level.WARNING, "Ignoring OTEL_EXPORTER_OTLP_HEADERS header {0}: contains control characters",
                        key);
                continue;
            }
            headers.put(key, value);
        }
        return headers;
    }

    /**
     * Renders the headers as a YAML {@code headers:} mapping for the OTLP exporter, or {@code ""} if there are none.
     * Entries are indented by six spaces; keys and values are single-quoted.
     */
    static String toYamlBlock(Map<String, String> headers) {
        if (headers.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("headers:");
        headers.forEach((k, v) -> sb.append('\n').append(INDENT).append(quote(k)).append(": ").append(quote(v)));
        return sb.toString();
    }

    private static String decode(String key, String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "OTEL_EXPORTER_OTLP_HEADERS header {0} has an invalid percent-encoding; "
                    + "using the value as is", key);
            return value;
        }
    }

    private static boolean hasControlCharacter(String s) {
        return s.chars().anyMatch(c -> c < 0x20 || c == 0x7f);
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
