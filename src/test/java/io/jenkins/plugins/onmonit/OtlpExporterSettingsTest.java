package io.jenkins.plugins.onmonit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OtlpExporterSettingsTest {

    private static final String ENDPOINT = "OTEL_EXPORTER_OTLP_ENDPOINT";
    private static final String METRICS_ENDPOINT = "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT";
    private static final String PROTOCOL = "OTEL_EXPORTER_OTLP_PROTOCOL";
    private static final String METRICS_PROTOCOL = "OTEL_EXPORTER_OTLP_METRICS_PROTOCOL";
    private static final String HEADERS = "OTEL_EXPORTER_OTLP_HEADERS";
    private static final String METRICS_HEADERS = "OTEL_EXPORTER_OTLP_METRICS_HEADERS";
    private static final String INSECURE = "OTEL_EXPORTER_OTLP_INSECURE";
    private static final String METRICS_INSECURE = "OTEL_EXPORTER_OTLP_METRICS_INSECURE";

    /** Settings resolved from alternating key/value pairs. */
    private static OtlpExporterSettings resolve(String... kv) {
        Map<String, String> env = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            env.put(kv[i], kv[i + 1]);
        }
        return OtlpExporterSettings.resolve(env);
    }

    @Test
    void unsetEverythingDefaultsToGrpcLocalhost() {
        var s = resolve();
        assertThat(s.protocol()).isEqualTo(OtlpProtocol.GRPC);
        assertThat(s.endpoint()).isEqualTo("http://localhost:4317");
        assertThat(s.endpointKey()).isEqualTo("endpoint");
        assertThat(s.insecure()).isTrue();
        assertThat(s.encoding()).isNull();
        assertThat(s.headers()).isEmpty();
        assertThat(s.warnings()).containsExactly("OTEL_EXPORTER_OTLP_ENDPOINT not set; using http://localhost:4317");
    }

    @Test
    void emptyEndpointIsUnset() {
        var s = resolve(ENDPOINT, "");
        assertThat(s.endpoint()).isEqualTo("http://localhost:4317");
        assertThat(s.warnings()).containsExactly("OTEL_EXPORTER_OTLP_ENDPOINT not set; using http://localhost:4317");
    }

    @Test
    void blankEndpointIsUnset() {
        var s = resolve(ENDPOINT, "  ");
        assertThat(s.endpoint()).isEqualTo("http://localhost:4317");
        assertThat(s.warnings()).containsExactly("OTEL_EXPORTER_OTLP_ENDPOINT not set; using http://localhost:4317");
    }

    @Test
    void httpProtocolDefaultsTo4318() {
        var s = resolve(PROTOCOL, "http/protobuf");
        assertThat(s.endpoint()).isEqualTo("http://localhost:4318");
        assertThat(s.warnings()).containsExactly("OTEL_EXPORTER_OTLP_ENDPOINT not set; using http://localhost:4318");
    }

    @Test
    void protocolValuesMapToExporters() {
        assertThat(resolve(PROTOCOL, "grpc").protocol().exporterName()).isEqualTo("otlp");
        assertThat(resolve(PROTOCOL, "http/protobuf").protocol().exporterName()).isEqualTo("otlphttp");
        var json = resolve(PROTOCOL, " HTTP/JSON ");
        assertThat(json.protocol()).isEqualTo(OtlpProtocol.HTTP_JSON);
        assertThat(json.protocol().exporterName()).isEqualTo("otlphttp");
        assertThat(json.encoding()).isEqualTo("json");
        assertThat(resolve(PROTOCOL, "http/protobuf").encoding()).isNull();
    }

    @Test
    void protocolEnvValues() {
        assertThat(OtlpProtocol.GRPC.envValue()).isEqualTo("grpc");
        assertThat(OtlpProtocol.HTTP_PROTOBUF.envValue()).isEqualTo("http/protobuf");
        assertThat(OtlpProtocol.HTTP_JSON.envValue()).isEqualTo("http/json");
        assertThat(OtlpProtocol.fromEnvValue("thrift")).isEmpty();
    }

    @Test
    void unknownProtocolWarnsAndUsesGrpc() {
        var s = resolve(ENDPOINT, "https://c:4317", PROTOCOL, "thrift");
        assertThat(s.protocol()).isEqualTo(OtlpProtocol.GRPC);
        assertThat(s.warnings()).containsExactly("Unsupported OTEL_EXPORTER_OTLP_PROTOCOL value 'thrift'; using grpc");
    }

    @Test
    void unknownMetricsProtocolWarningNamesMetricsVariable() {
        var s = resolve(ENDPOINT, "https://c:4317", METRICS_PROTOCOL, "thrift");
        assertThat(s.warnings())
                .containsExactly("Unsupported OTEL_EXPORTER_OTLP_METRICS_PROTOCOL value 'thrift'; using grpc");
    }

    @Test
    void metricsProtocolWinsOverGeneral() {
        assertThat(resolve(METRICS_PROTOCOL, "http/protobuf", PROTOCOL, "grpc").protocol())
                .isEqualTo(OtlpProtocol.HTTP_PROTOBUF);
    }

    @Test
    void emptyMetricsProtocolFallsBackToGeneral() {
        assertThat(resolve(METRICS_PROTOCOL, "", PROTOCOL, "http/protobuf").protocol())
                .isEqualTo(OtlpProtocol.HTTP_PROTOBUF);
    }

    @Test
    void metricsEndpointWinsOverGeneral() {
        var s = resolve(
                ENDPOINT, "http://general:4318",
                METRICS_ENDPOINT, "http://metrics:4318/v1/metrics",
                PROTOCOL, "http/protobuf");
        assertThat(s.endpoint()).isEqualTo("http://metrics:4318/v1/metrics");
        assertThat(s.endpointKey()).isEqualTo("metrics_endpoint");
        assertThat(s.warnings()).isEmpty();
    }

    @Test
    void metricsEndpointAloneIsEnough() {
        var s = resolve(METRICS_ENDPOINT, "http://metrics:4318/v1/metrics", PROTOCOL, "http/protobuf");
        assertThat(s.endpoint()).isEqualTo("http://metrics:4318/v1/metrics");
        assertThat(s.warnings()).isEmpty();
    }

    @Test
    void grpcMetricsEndpointUsesEndpointKey() {
        var s = resolve(METRICS_ENDPOINT, "http://m:4317");
        assertThat(s.endpoint()).isEqualTo("http://m:4317");
        assertThat(s.endpointKey()).isEqualTo("endpoint");
    }

    @Test
    void generalEndpointUsesEndpointKeyForHttp() {
        assertThat(resolve(ENDPOINT, "http://c:4318", PROTOCOL, "http/protobuf").endpointKey())
                .isEqualTo("endpoint");
    }

    @Test
    void metricsHeadersReplaceGeneral() {
        var s = resolve(ENDPOINT, "https://c:4317", HEADERS, "a=1,b=2", METRICS_HEADERS, "c=3");
        assertThat(s.headers()).containsExactly(entry("c", "3"));
    }

    @Test
    void blankMetricsHeadersFallBackToGeneral() {
        var s = resolve(ENDPOINT, "https://c:4317", HEADERS, "a=1", METRICS_HEADERS, " ");
        assertThat(s.headers()).containsExactly(entry("a", "1"));
    }

    @Test
    void httpsGrpcIsNotInsecure() {
        assertThat(resolve(ENDPOINT, "https://c:4317").insecure()).isFalse();
    }

    @Test
    void upperCaseHttpSchemeIsInsecure() {
        assertThat(resolve(ENDPOINT, "HTTP://c:4317").insecure()).isTrue();
    }

    @Test
    void httpProtocolIsNeverInsecure() {
        assertThat(resolve(ENDPOINT, "http://c:4318", PROTOCOL, "http/protobuf").insecure())
                .isFalse();
    }

    @Test
    void schemelessEndpointHonoursInsecure() {
        assertThat(resolve(ENDPOINT, "collector:4317", INSECURE, "TRUE").insecure())
                .isTrue();
    }

    @Test
    void schemeWinsOverInsecure() {
        assertThat(resolve(ENDPOINT, "https://c:4317", INSECURE, "true").insecure())
                .isFalse();
        assertThat(resolve(ENDPOINT, "http://c:4317", INSECURE, "false").insecure())
                .isTrue();
    }

    @Test
    void metricsInsecureWinsOverGeneral() {
        assertThat(resolve(ENDPOINT, "collector:4317", INSECURE, "true", METRICS_INSECURE, "false")
                        .insecure())
                .isFalse();
        assertThat(resolve(ENDPOINT, "collector:4317", INSECURE, "false", METRICS_INSECURE, "true")
                        .insecure())
                .isTrue();
    }

    @Test
    void insecureIgnoredForHttpProtocol() {
        assertThat(resolve(ENDPOINT, "collector:4318", PROTOCOL, "http/protobuf", INSECURE, "true")
                        .insecure())
                .isFalse();
    }

    @Test
    void invalidInsecureWarnsAndIsFalse() {
        var s = resolve(ENDPOINT, "collector:4317", INSECURE, "yes");
        assertThat(s.insecure()).isFalse();
        assertThat(s.warnings())
                .containsExactly("Invalid OTEL_EXPORTER_OTLP_INSECURE value 'yes'; treating it as false");
    }

    @Test
    void explicitFalseInsecureDoesNotWarn() {
        var s = resolve(ENDPOINT, "collector:4317", INSECURE, " False ");
        assertThat(s.insecure()).isFalse();
        assertThat(s.warnings()).isEmpty();
    }

    /** OTLP/HTTP needs a URL: otlphttp reads "collector:4318" as scheme "collector" and every export fails. */
    @Test
    void schemelessEndpointWithHttpProtocolWarns() {
        var s = resolve(ENDPOINT, "collector:4318", PROTOCOL, "http/protobuf");
        assertThat(s.endpoint()).isEqualTo("collector:4318");
        assertThat(s.warnings())
                .containsExactly("OTEL_EXPORTER_OTLP_ENDPOINT has no http:// or https:// scheme, which the "
                        + "http/protobuf protocol requires; exports will fail");
    }

    @Test
    void schemelessMetricsEndpointWithHttpProtocolWarningNamesMetricsVariable() {
        var s = resolve(METRICS_ENDPOINT, "collector:4318/v1/metrics", PROTOCOL, "http/json");
        assertThat(s.warnings())
                .containsExactly("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT has no http:// or https:// scheme, which the "
                        + "http/json protocol requires; exports will fail");
    }

    @Test
    void schemelessEndpointIsLeftAlone() {
        var s = resolve(ENDPOINT, "collector:4317");
        assertThat(s.endpoint()).isEqualTo("collector:4317");
        assertThat(s.insecure()).isFalse();
        assertThat(s.warnings()).isEmpty();
    }

    @Test
    void defaultPortMadeExplicitAndRootSlashStripped() {
        assertThat(resolve(ENDPOINT, "http://collector/").endpoint()).isEqualTo("http://collector:80");
    }
}
