package io.jenkins.plugins.onmonit.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the PromQL of a Grafana dashboard JSON and substitutes its template variables the way Grafana would. */
public final class DashboardQueries {

    /** A {@code label_values(<metricSelector>, <label>)} query variable. */
    public record LabelValuesQuery(String variable, String metricSelector, String label) {}

    /** Number of panel {@code expr}s in {@link #repoDashboard()}. */
    public static final int REPO_DASHBOARD_EXPRS = 270;

    /** Number of those that are {@linkplain #isCore core}. */
    public static final int REPO_DASHBOARD_CORE_EXPRS = 27;

    /** Linux node_exporter metrics that must have data while a build is monitored. */
    public static final Set<String> CORE_METRICS = Set.of(
            "node_cpu_seconds_total",
            "node_memory_MemTotal_bytes",
            "node_memory_MemAvailable_bytes",
            "node_load1",
            "node_filesystem_avail_bytes",
            "node_network_receive_bytes_total",
            "node_disk_read_bytes_total");

    /** Grafana built-ins fixed for tests, matching a 2s scrape interval. */
    static final Map<String, String> BUILT_INS = Map.of("__rate_interval", "10s", "__interval", "2s", "__range", "1m");

    private static final Pattern LABEL_VALUES =
            Pattern.compile("^\\s*label_values\\((.*),\\s*([A-Za-z_]\\w*)\\s*\\)\\s*$");
    private static final Pattern METRIC = Pattern.compile("\\bnode_\\w+");

    private final List<String> panelExprs;
    private final List<LabelValuesQuery> labelValuesVariables;
    private final Map<String, String> fixed;

    private DashboardQueries(
            List<String> panelExprs, List<LabelValuesQuery> labelValuesVariables, Map<String, String> fixed) {
        this.panelExprs = List.copyOf(panelExprs);
        this.labelValuesVariables = List.copyOf(labelValuesVariables);
        this.fixed = Map.copyOf(fixed);
    }

    /** {@code node-exporter-full-ci.json} at the repository root. */
    public static Path repoDashboard() {
        return Path.of(System.getProperty("basedir", ".")).resolve("node-exporter-full-ci.json");
    }

    public static DashboardQueries load(Path dashboardJson) throws IOException {
        JsonNode root = new ObjectMapper().readTree(dashboardJson.toFile());
        List<String> exprs = new ArrayList<>();
        collectExprs(root, exprs);

        List<LabelValuesQuery> labelValues = new ArrayList<>();
        Map<String, String> fixed = new LinkedHashMap<>(BUILT_INS);
        for (JsonNode v : root.path("templating").path("list")) {
            String name = v.path("name").asText();
            String type = v.path("type").asText();
            JsonNode query = v.path("query");
            String text = query.isObject() ? query.path("query").asText() : query.asText();
            if ("query".equals(type)) {
                Matcher m = LABEL_VALUES.matcher(text);
                if (m.matches()) {
                    labelValues.add(new LabelValuesQuery(name, m.group(1).trim(), m.group(2)));
                }
            } else if ("custom".equals(type) || "constant".equals(type)) {
                fixed.put(name, text);
            }
        }
        return new DashboardQueries(exprs, labelValues, fixed);
    }

    private static void collectExprs(JsonNode node, List<String> out) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                if ("expr".equals(e.getKey()) && e.getValue().isTextual()) {
                    out.add(e.getValue().asText());
                } else {
                    collectExprs(e.getValue(), out);
                }
            });
        } else if (node.isArray()) {
            node.forEach(n -> collectExprs(n, out));
        }
    }

    /** Every {@code expr} in the dashboard, in document order. */
    public List<String> panelExprs() {
        return panelExprs;
    }

    /** The {@code label_values(...)} query variables, in dashboard order. */
    public List<LabelValuesQuery> labelValuesVariables() {
        return labelValuesVariables;
    }

    /**
     * Replaces {@code $name} and {@code ${name}} with {@code vars}, the fixed built-ins and the dashboard's custom
     * variables (explicit {@code vars} win). Longer names are replaced first so {@code $jobid} is not hit by
     * {@code $job}.
     */
    public String substitute(String expr, Map<String, String> vars) {
        return substitution(vars).apply(expr);
    }

    /** Like {@link #substitute(String, Map)} for many expressions: the variable names are sorted once. */
    public UnaryOperator<String> substitution(Map<String, String> vars) {
        Map<String, String> all = new LinkedHashMap<>(fixed);
        all.putAll(vars);
        List<String> names = new ArrayList<>(all.keySet());
        names.sort(Comparator.comparingInt(String::length).reversed());
        return expr -> {
            String result = expr;
            for (String name : names) {
                String value = all.get(name);
                result = result.replace("${" + name + "}", value).replace("$" + name, value);
            }
            return result;
        };
    }

    /** Whether every node_* metric in {@code expr} is one of {@link #CORE_METRICS}, and there is at least one. */
    public static boolean isCore(String expr) {
        Matcher m = METRIC.matcher(expr);
        boolean any = false;
        while (m.find()) {
            if (!CORE_METRICS.contains(m.group())) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /** {@code t} as Prometheus API time: decimal seconds, never in scientific notation. */
    public static String promTime(Instant t) {
        return BigDecimal.valueOf(t.toEpochMilli()).movePointLeft(3).toPlainString();
    }
}
