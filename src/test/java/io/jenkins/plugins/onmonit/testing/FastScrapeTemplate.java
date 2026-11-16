package io.jenkins.plugins.onmonit.testing;

import io.jenkins.plugins.onmonit.ONMonitConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Derives variants of the built-in otelcol template for tests: faster scraping, other exporters, extra resource
 * attributes.
 */
public final class FastScrapeTemplate {

    private static final String RESOURCE = "/io/jenkins/plugins/onmonit/otel.yaml.tmpl";

    private FastScrapeTemplate() {}

    /**
     * Inserts a {@code resource/ci} attribute. {@code value} is written inside double quotes and may contain template
     * expressions such as {@code [[${env.BUILD_ID}]]}.
     */
    public static String withResourceAttribute(String template, String key, String value) {
        String anchor = "  resource/ci:\n    attributes:\n";
        return replaceOrFail(
                template,
                anchor,
                anchor + "    - action: insert\n      key: " + key + "\n      value: \"" + value + "\"\n");
    }

    public static String withInterval(String interval) {
        return replaceOrFail(builtIn(), "scrape_interval: 15s", "scrape_interval: " + interval);
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

    /**
     * The built-in template with {@code \n} line endings. A Windows checkout with {@code core.autocrlf} has
     * {@code \r\n} in the resource, which the {@code \n}-based replacements here would not match.
     */
    public static String builtIn() {
        try (InputStream in = FastScrapeTemplate.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
