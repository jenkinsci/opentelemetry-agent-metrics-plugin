package io.jenkins.plugins.onmonit.testing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

/** Typed view over a rendered OpenTelemetry collector configuration. */
public final class RenderedConfig {

    private final Map<String, Object> root;

    private RenderedConfig(Map<String, Object> root) {
        this.root = root;
    }

    /** Parses the YAML, throwing if it is invalid. */
    public static RenderedConfig parse(String yaml) {
        Object loaded = new Yaml().load(yaml);
        if (!(loaded instanceof Map)) {
            throw new IllegalArgumentException("Rendered config is not a YAML mapping");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) loaded;
        return new RenderedConfig(map);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Collections.emptyMap();
    }

    private Map<String, Object> section(String name) {
        return map(root.get(name));
    }

    /** First static scrape target, e.g. {@code localhost:9100}. */
    public String scrapeTarget() {
        Map<String, Object> config = map(map(section("receivers").get("prometheus/ci")).get("config"));
        List<?> scrapeConfigs = (List<?>) config.get("scrape_configs");
        Map<String, Object> scrape = map(scrapeConfigs.get(0));
        List<?> staticConfigs = (List<?>) scrape.get("static_configs");
        List<?> targets = (List<?>) map(staticConfigs.get(0)).get("targets");
        return String.valueOf(targets.get(0));
    }

    /** Value of the {@code resource/ci} attribute with the given key, or null. */
    public String resourceAttribute(String key) {
        List<?> attributes = (List<?>) map(section("processors").get("resource/ci")).get("attributes");
        if (attributes == null) {
            return null;
        }
        for (Object a : attributes) {
            Map<String, Object> attr = map(a);
            if (key.equals(attr.get("key"))) {
                Object v = attr.get("value");
                return v == null ? null : String.valueOf(v);
            }
        }
        return null;
    }

    public String otlpEndpoint() {
        return String.valueOf(map(section("exporters").get("otlp")).get("endpoint"));
    }

    /** OTLP exporter headers; empty if absent. */
    public Map<String, String> otlpHeaders() {
        Object headers = map(section("exporters").get("otlp")).get("headers");
        Map<String, String> result = new LinkedHashMap<>();
        map(headers).forEach((k, v) -> result.put(k, String.valueOf(v)));
        return result;
    }

    public Set<String> exporterNames() {
        return section("exporters").keySet();
    }
}
