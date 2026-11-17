package io.jenkins.plugins.onmonit;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The OTLP metrics exporter settings resolved from the {@code OTEL_EXPORTER_OTLP_*} variables of the
 * <a href="https://opentelemetry.io/docs/specs/otel/protocol/exporter/">OTLP exporter spec</a>.
 *
 * <p>For each setting, the metrics-specific variable ({@code OTEL_EXPORTER_OTLP_METRICS_*}) wins over the general one,
 * and an empty or blank value counts as unset. Problems are collected in {@link #warnings()} for the build log.
 */
final class OtlpExporterSettings {

    private static final String PREFIX = "OTEL_EXPORTER_OTLP_";
    private static final String METRICS_PREFIX = "OTEL_EXPORTER_OTLP_METRICS_";
    private static final String ENDPOINT = "ENDPOINT";

    private final OtlpProtocol protocol;
    private final String endpoint;
    private final boolean metricsEndpoint;
    private final boolean insecureVariable;
    private final Map<String, String> headers;
    private final List<String> warnings;

    private OtlpExporterSettings(
            OtlpProtocol protocol,
            String endpoint,
            boolean metricsEndpoint,
            boolean insecureVariable,
            Map<String, String> headers,
            List<String> warnings) {
        this.protocol = protocol;
        this.endpoint = endpoint;
        this.metricsEndpoint = metricsEndpoint;
        this.insecureVariable = insecureVariable;
        this.headers = Collections.unmodifiableMap(headers);
        this.warnings = Collections.unmodifiableList(warnings);
    }

    /** Resolves the settings from a build environment. Never throws. */
    static OtlpExporterSettings resolve(Map<String, String> env) {
        List<String> warnings = new ArrayList<>();

        OtlpProtocol protocol = OtlpProtocol.GRPC;
        String protocolVar = chooseVariable(env, "PROTOCOL");
        if (protocolVar != null) {
            String value = env.get(protocolVar);
            protocol = OtlpProtocol.fromEnvValue(value).orElse(null);
            if (protocol == null) {
                warnings.add("Unsupported " + protocolVar + " value '" + value.trim() + "'; using "
                        + OtlpProtocol.GRPC.envValue());
                protocol = OtlpProtocol.GRPC;
            }
        }

        String endpointVar = chooseVariable(env, ENDPOINT);
        String endpoint;
        if (endpointVar == null) {
            endpoint = protocol.defaultEndpoint();
            warnings.add(PREFIX + ENDPOINT + " not set; using " + endpoint);
        } else {
            endpoint = toOtelCompatibleUrl(env.get(endpointVar).trim());
            if (protocol != OtlpProtocol.GRPC && !hasHttpScheme(endpoint)) {
                warnings.add(endpointVar + " has no http:// or https:// scheme, which the " + protocol.envValue()
                        + " protocol requires; exports will fail");
            }
        }

        boolean insecure = false;
        String insecureVar = chooseVariable(env, "INSECURE");
        if (insecureVar != null) {
            String value = env.get(insecureVar).trim();
            insecure = value.equalsIgnoreCase("true");
            if (!insecure && !value.equalsIgnoreCase("false")) {
                warnings.add("Invalid " + insecureVar + " value '" + value + "'; treating it as false");
            }
        }

        String headersVar = chooseVariable(env, "HEADERS");
        Map<String, String> headers = OtlpHeaders.parse(headersVar == null ? null : env.get(headersVar));

        return new OtlpExporterSettings(
                protocol, endpoint, endpointVar != null && endpointVar.startsWith(METRICS_PREFIX), insecure,
                headers, warnings);
    }

    /** The metrics-specific variable for {@code suffix} if set and not blank, else the general one, else null. */
    @CheckForNull
    private static String chooseVariable(Map<String, String> env, String suffix) {
        for (String name : new String[] {METRICS_PREFIX + suffix, PREFIX + suffix}) {
            String value = env.get(name);
            if (value != null && !value.isBlank()) {
                return name;
            }
        }
        return null;
    }

    /** Makes the default port explicit and strips a bare {@code /} path; returns a non-URL unchanged. */
    private static String toOtelCompatibleUrl(String urlStr) {
        try {
            URL url = new URL(urlStr);
            if (url.getPort() == -1) {
                url = new URL(url.getProtocol(), url.getHost(), url.getDefaultPort(), url.getFile());
            }
            if ("/".equals(url.getFile())) {
                url = new URL(url.getProtocol(), url.getHost(), url.getPort(), "");
            }
            return url.toString();
        } catch (MalformedURLException e) {
            return urlStr;
        }
    }

    OtlpProtocol protocol() {
        return protocol;
    }

    String endpoint() {
        return endpoint;
    }

    /**
     * The exporter key for {@link #endpoint()}: {@code metrics_endpoint} when the endpoint came from
     * {@code OTEL_EXPORTER_OTLP_METRICS_ENDPOINT} and the exporter is otlphttp (used as-is, no {@code /v1/metrics}
     * appended), else {@code endpoint}.
     */
    String endpointKey() {
        return metricsEndpoint && protocol != OtlpProtocol.GRPC ? "metrics_endpoint" : "endpoint";
    }

    /**
     * Whether the gRPC exporter must connect without TLS: an {@code http://} endpoint, or an endpoint without
     * {@code http(s)://} scheme when {@code OTEL_EXPORTER_OTLP_INSECURE} is true. Always false for otlphttp, which
     * follows the URL scheme.
     */
    boolean insecure() {
        if (protocol != OtlpProtocol.GRPC) {
            return false;
        }
        if (endpoint.toLowerCase(Locale.ROOT).startsWith("http://")) {
            return true;
        }
        return !hasHttpScheme(endpoint) && insecureVariable;
    }

    private static boolean hasHttpScheme(String endpoint) {
        String lower = endpoint.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** {@code json} for {@code http/json}, else null. */
    @CheckForNull
    String encoding() {
        return protocol == OtlpProtocol.HTTP_JSON ? "json" : null;
    }

    Map<String, String> headers() {
        return headers;
    }

    /** Messages for the build log, without the {@code [on-monit]} prefix. */
    List<String> warnings() {
        return warnings;
    }
}
