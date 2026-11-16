package io.jenkins.plugins.onmonit;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.Locale;
import java.util.Optional;

/**
 * The OTLP transport protocols of {@code OTEL_EXPORTER_OTLP_PROTOCOL}, with the otelcol exporter that implements each.
 */
enum OtlpProtocol {
    GRPC("grpc", "otlp", "http://localhost:4317"),
    HTTP_PROTOBUF("http/protobuf", "otlphttp", "http://localhost:4318"),
    HTTP_JSON("http/json", "otlphttp", "http://localhost:4318");

    private final String envValue;
    private final String exporterName;
    private final String defaultEndpoint;

    OtlpProtocol(String envValue, String exporterName, String defaultEndpoint) {
        this.envValue = envValue;
        this.exporterName = exporterName;
        this.defaultEndpoint = defaultEndpoint;
    }

    /** The value as written in {@code OTEL_EXPORTER_OTLP_PROTOCOL}. */
    String envValue() {
        return envValue;
    }

    /** The otelcol exporter type for this protocol. */
    String exporterName() {
        return exporterName;
    }

    /** The OTLP spec's default endpoint for this protocol. */
    String defaultEndpoint() {
        return defaultEndpoint;
    }

    /** The protocol for an env value, ignoring case and surrounding whitespace. */
    static Optional<OtlpProtocol> fromEnvValue(@CheckForNull String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        for (OtlpProtocol protocol : values()) {
            if (protocol.envValue.equals(normalised)) {
                return Optional.of(protocol);
            }
        }
        return Optional.empty();
    }
}
