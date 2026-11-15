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
                .contains("    endpoint: \"[[${otlpEndpoint}]]\"\n    tls:\n      insecure: true\n");
    }

    @Test
    void builtInHasUnixLineEndings() {
        assertThat(FastScrapeTemplate.builtIn()).doesNotContain("\r");
    }

    @Test
    void failsOnTemplateDrift() {
        assertThatThrownBy(() -> FastScrapeTemplate.replaceOrFail("no interval here", "scrape_interval: 15s", "x"))
                .isInstanceOf(IllegalStateException.class);
    }
}
