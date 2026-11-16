package io.jenkins.plugins.onmonit.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FastScrapeTemplateTest {

    @Test
    void replacesInterval() {
        String t = FastScrapeTemplate.withInterval("2s");
        assertThat(t).contains("scrape_interval: 2s").doesNotContain("15s");
    }

    @Test
    void otlpHttpVariant() {
        String t = FastScrapeTemplate.withIntervalAndOtlpHttp("2s");
        assertThat(t)
                .contains("scrape_interval: 2s")
                .contains("otlphttp:")
                .contains("exporters: [otlphttp, debug]")
                .doesNotContain("  otlp:\n");
    }

    @Test
    void insecureTlsVariant() {
        String t = FastScrapeTemplate.withIntervalAndInsecureTls("2s");
        assertThat(t)
                .contains("scrape_interval: 2s")
                .contains("    endpoint: [(${yaml.quote(otlpEndpoint)})]\n    tls:\n      insecure: true\n");
    }

    /** Another exporter with the same endpoint line must not get the gRPC exporter's TLS setting. */
    @Test
    void insecureTlsSitsUnderOtlpExporterOnly() {
        String endpoint = "    endpoint: [(${yaml.quote(otlpEndpoint)})]\n";
        String t = FastScrapeTemplate.withInsecureTls("exporters:\n  otlphttp:\n" + endpoint + "  otlp:\n" + endpoint);
        assertThat(t)
                .isEqualTo("exporters:\n  otlphttp:\n" + endpoint + "  otlp:\n" + endpoint
                        + "    tls:\n      insecure: true\n");
    }

    @Test
    void builtInHasUnixLineEndings() {
        assertThat(FastScrapeTemplate.builtIn()).doesNotContain("\r");
    }

    @Test
    void resourceAttributeVariant() {
        String t = FastScrapeTemplate.withResourceAttribute(FastScrapeTemplate.builtIn(), "test.custom.label", "v");
        assertThat(t).contains("  resource/ci:\n    attributes:\n"
                + "    - action: insert\n      key: test.custom.label\n      value: \"v\"\n"
                + "    - action: insert\n");
    }

    @Test
    void failsOnTemplateDrift() {
        assertThatThrownBy(() -> FastScrapeTemplate.replaceOrFail("no interval here", "scrape_interval: 15s", "x"))
                .isInstanceOf(IllegalStateException.class);
    }
}
