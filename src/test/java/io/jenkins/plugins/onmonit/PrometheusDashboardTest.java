package io.jenkins.plugins.onmonit;

import static io.jenkins.plugins.onmonit.testing.JenkinsFixtures.globalEnv;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.Functions;
import hudson.slaves.DumbSlave;
import io.jenkins.plugins.onmonit.testing.DashboardQueries;
import io.jenkins.plugins.onmonit.testing.DashboardQueries.LabelValuesQuery;
import io.jenkins.plugins.onmonit.testing.FastScrapeTemplate;
import io.jenkins.plugins.onmonit.testing.HeldBuilds;
import io.jenkins.plugins.onmonit.testing.JenkinsFixtures;
import io.jenkins.plugins.onmonit.testing.Lifecycle;
import io.jenkins.plugins.onmonit.testing.RealBinaries;
import io.jenkins.plugins.onmonit.testing.TestAgents;
import io.jenkins.plugins.onmonit.util.RemoteComputerInfoRetriever;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Runs a build with the real collector and node_exporter exporting over OTLP/HTTP into a Prometheus reference
 * backend, then runs every query of {@code node-exporter-full-ci.json} against it while the build is still held inside
 * {@code onMonit}. Local-only:
 * {@code ./mvnw verify -Preal-binaries -Dtest=PrometheusDashboardTest}.
 */
@Tag("real-binaries")
@WithJenkins
@Testcontainers(disabledWithoutDocker = true)
class PrometheusDashboardTest {

    private static final String IMAGE = System.getProperty("onmonit.test.prometheus.image", "prom/prometheus:v3.16.0");
    private static final String CONFIG = System.getProperty("onmonit.test.prometheus.config");
    private static final String CONFIG_RESOURCE = "io/jenkins/plugins/onmonit/testing/prometheus-reference.yml";
    private static final String CONFIG_IN_CONTAINER = "/etc/prometheus/prometheus.yml";
    private static final Set<String> CORE_METRICS = Set.of(
            "node_cpu_seconds_total",
            "node_memory_MemTotal_bytes",
            "node_memory_MemAvailable_bytes",
            "node_load1",
            "node_filesystem_avail_bytes",
            "node_network_receive_bytes_total",
            "node_disk_read_bytes_total");
    private static final Pattern METRIC = Pattern.compile("\\bnode_\\w+");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    private GenericContainer<?> prometheus;
    private final HttpClient http = HttpClient.newHttpClient();
    private JenkinsRule jenkins;
    private DumbSlave agent;

    /** Upper bound for start-up (uploading and unpacking the binaries, starting otelcol) plus enough samples. */
    private static final Duration READY = Duration.ofSeconds(120);

    /**
     * A build held inside {@code onMonit}, its dashboard variables, and the time to evaluate queries at: a moment
     * when Prometheus already had several samples for the build.
     */
    private record Build(WorkflowJob job, WorkflowRun run, Map<String, String> vars, double evalSeconds) {}

    @BeforeAll
    static void installBinaries() throws Exception {
        assumeFalse(Functions.isWindows());
        RealBinaries.installResourceRoot(TestAgents.jenkinsHomeEnv());
    }

    @BeforeEach
    void startPrometheus() {
        MountableFile config = CONFIG != null
                ? MountableFile.forHostPath(Path.of(CONFIG), 0644)
                : MountableFile.forClasspathResource(CONFIG_RESOURCE, 0644);
        prometheus = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withCopyFileToContainer(config, CONFIG_IN_CONTAINER)
                .withCommand("--config.file=" + CONFIG_IN_CONTAINER, "--web.enable-otlp-receiver")
                .withExposedPorts(9090)
                .waitingFor(Wait.forHttp("/-/ready").forStatusCode(200));
        prometheus.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (agent != null) {
                HeldBuilds.releaseAll(jenkins, agent, Duration.ofMinutes(1));
                String root = Path.of(agent.getRemoteFS()).toAbsolutePath().toString();
                Awaitility.await()
                        .atMost(Duration.ofSeconds(15))
                        .untilAsserted(() -> assertThat(Lifecycle.processesUnder(root))
                                .as("otelcol/exporter processes left running under " + root)
                                .isEmpty());
            }
        } finally {
            if (prometheus != null) {
                prometheus.stop();
            }
        }
    }

    private String baseUrl() {
        return "http://localhost:" + prometheus.getMappedPort(9090);
    }

    /**
     * Starts {@code f/p} held inside {@code onMonit}, waits until Prometheus has at least 3 samples within the
     * dashboard's 10s rate interval, and resolves the dashboard variables for it. Queries are evaluated while the
     * collector still runs: at build end the collector's shutdown may have marked the series stale, which empties
     * instant queries.
     */
    private Build runBuild(JenkinsRule j) throws Exception {
        jenkins = j;
        FastScrapeTemplate.apply(FastScrapeTemplate.withIntervalAndOtlpHttp("2s"));
        globalEnv(j, "OTEL_EXPORTER_OTLP_ENDPOINT", baseUrl() + "/api/v1/otlp");
        Path empty = TestAgents.emptyDir(tmp);
        agent = TestAgents.agent(j, "t", empty, empty);

        Folder folder = j.createProject(Folder.class, "f");
        WorkflowJob job = folder.createProject(WorkflowJob.class, "p");
        job.setDefinition(new CpsFlowDefinition("node('t') { onMonit {\n" + HeldBuilds.HOLD + "\n} }", true));
        WorkflowRun run = job.scheduleBuild2(0).waitForStart();

        String runSelector = "node_uname_info{cicd_pipeline_run_id=\"" + run.getId() + "\"}";
        String ready = "min(count_over_time(node_load1{cicd_pipeline_run_id=\"" + run.getId() + "\"}[10s])) >= 3";
        double[] evalSeconds = {0};
        Awaitility.await().atMost(READY).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            assertThat(run.isBuilding()).as("build still held inside onMonit").isTrue();
            double now = System.currentTimeMillis() / 1000.0;
            JsonNode body = get("/api/v1/query", Map.of("query", ready, "time", String.valueOf(now)));
            assertThat(body.path("data").path("result").isEmpty()).as(ready + ": " + body).isFalse();
            evalSeconds[0] = now;
        });
        List<String> workers = labelValues("cicd_worker_id", runSelector);
        assertThat(workers).as("cicd_worker_id of " + runSelector).hasSize(1);
        List<Integer> ports = JenkinsFixtures.ports(JenkinsRule.getLog(run));
        assertThat(ports).as("node_exporter port in build log").isNotEmpty();
        assertThat(workers.get(0)).endsWith(":" + ports.get(0));

        Map<String, String> vars = Map.of(
                "job", job.getFullName(),
                "jobid", run.getId(),
                "jobgroup", folder.getFullName(),
                "workerid", workers.get(0));
        return new Build(job, run, vars, evalSeconds[0]);
    }

    /** Releases the held build and checks it succeeds. */
    private void finish(Build build) throws Exception {
        HeldBuilds.releaseOn(agent, build.job());
        jenkins.assertBuildStatusSuccess(jenkins.waitForCompletion(build.run()));
    }

    private JsonNode get(String path, Map<String, String> params) throws IOException, InterruptedException {
        String query = params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl() + path + "?" + query))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return JSON.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
    }

    private List<String> labelValues(String label, String selector) throws IOException, InterruptedException {
        JsonNode body = get("/api/v1/label/" + label + "/values", Map.of("match[]", selector));
        assertThat(body.path("status").asText()).as("label values " + label + " " + selector + ": " + body)
                .isEqualTo("success");
        List<String> values = new ArrayList<>();
        body.path("data").forEach(v -> values.add(v.asText()));
        return values;
    }

    private static boolean isCore(String expr) {
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

    @Test
    void labelVariablesResolve(JenkinsRule j) throws Exception {
        Build build = runBuild(j);
        DashboardQueries dashboard = DashboardQueries.load(DashboardQueries.repoDashboard());
        assertThat(dashboard.labelValuesVariables()).isNotEmpty();
        for (LabelValuesQuery v : dashboard.labelValuesVariables()) {
            String selector = dashboard.substitute(v.metricSelector(), build.vars());
            assertThat(labelValues(v.label(), selector))
                    .as("label_values(%s, %s) for $%s", selector, v.label(), v.variable())
                    .contains(build.vars().get(v.variable()));
        }
        finish(build);
    }

    @Test
    void allPanelQueriesExecuteAndCoreReturnData(JenkinsRule j) throws Exception {
        Build build = runBuild(j);
        DashboardQueries dashboard = DashboardQueries.load(DashboardQueries.repoDashboard());
        List<String> exprs = dashboard.panelExprs();
        assertThat(exprs).hasSize(275);

        List<String> coverage = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> emptyCore = new ArrayList<>();
        int hasData = 0;
        int empty = 0;
        int core = 0;
        for (String expr : exprs) {
            String q = dashboard.substitute(expr, build.vars());
            String line = q.replaceAll("\\s*\\n\\s*", " ").strip();
            JsonNode body = get("/api/v1/query", Map.of("query", q, "time", String.valueOf(build.evalSeconds())));
            if (!"success".equals(body.path("status").asText())) {
                errors.add(body.path("errorType").asText() + ": " + body.path("error").asText() + "\n    " + q);
                coverage.add("ERROR\t" + line);
                continue;
            }
            boolean data = !body.path("data").path("result").isEmpty();
            if (data) {
                hasData++;
            } else {
                empty++;
            }
            coverage.add((data ? "HAS_DATA" : "EMPTY") + "\t" + line);
            if (isCore(expr)) {
                core++;
                if (!data) {
                    emptyCore.add(q);
                }
            }
        }
        finish(build);
        Path out = Path.of(System.getProperty("basedir", "."), "target", "dashboard-coverage.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, coverage, StandardCharsets.UTF_8);
        System.out.printf(
                "Dashboard coverage: %d queries, %d HAS_DATA, %d EMPTY, %d ERROR, %d core (%d empty); see %s%n",
                exprs.size(), hasData, empty, errors.size(), core, emptyCore.size(), out);

        assertThat(errors).as("queries Prometheus rejected").isEmpty();
        assertThat(core).as("core queries").isPositive();
        // The core metric set is Linux's (e.g. macOS has node_memory_total_bytes, not MemTotal/MemAvailable), so
        // empty core queries fail the test on Linux only; elsewhere they are only reported.
        if ("linux".equals(RemoteComputerInfoRetriever.getOs())) {
            assertThat(emptyCore).as("core queries without data while monitoring").isEmpty();
        } else {
            emptyCore.forEach(q -> System.out.println("EMPTY core query (not enforced on "
                    + RemoteComputerInfoRetriever.getOs() + "): " + q.replaceAll("\\s*\\n\\s*", " ").strip()));
        }
    }
}
