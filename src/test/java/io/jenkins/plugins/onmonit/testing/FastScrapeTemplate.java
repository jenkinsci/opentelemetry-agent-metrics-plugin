package io.jenkins.plugins.onmonit.testing;

import io.jenkins.plugins.onmonit.ONMonitConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Derives faster-scraping variants of the built-in otelcol template for tests. */
public final class FastScrapeTemplate {

    private static final String RESOURCE = "/io/jenkins/plugins/onmonit/otel.yaml.tmpl";

    private FastScrapeTemplate() {}

    public static String withInterval(String interval) {
        return replaceOrFail(builtIn(), "scrape_interval: 15s", "scrape_interval: " + interval);
    }

    public static String withIntervalAndOtlpHttp(String interval) {
        String t = withInterval(interval);
        t = replaceOrFail(t, "  otlp:\n", "  otlphttp:\n");
        return replaceOrFail(t, "exporters: [otlp, debug]", "exporters: [otlphttp, debug]");
    }

    public static void apply(String template) {
        ONMonitConfig.get().setOtelConfigTemplate(template);
    }

    static String replaceOrFail(String text, String from, String to) {
        if (!text.contains(from)) {
            throw new IllegalStateException("Template no longer contains '" + from + "'; update FastScrapeTemplate");
        }
        return text.replace(from, to);
    }

    private static String builtIn() {
        try (InputStream in = FastScrapeTemplate.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
