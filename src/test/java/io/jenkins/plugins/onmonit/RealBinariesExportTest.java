package io.jenkins.plugins.onmonit;

import static io.jenkins.plugins.onmonit.testing.JenkinsFixtures.globalEnv;
import static org.assertj.core.api.Assertions.assertThat;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.slaves.DumbSlave;
import io.jenkins.plugins.onmonit.testing.FastScrapeTemplate;
import io.jenkins.plugins.onmonit.testing.HeldBuilds;
import io.jenkins.plugins.onmonit.testing.JenkinsFixtures;
import io.jenkins.plugins.onmonit.testing.Lifecycle;
import io.jenkins.plugins.onmonit.testing.OtlpMetricsReceiver;
import io.jenkins.plugins.onmonit.testing.RealBinaries;
import io.jenkins.plugins.onmonit.testing.TestAgents;
import io.jenkins.plugins.onmonit.util.RemoteComputerInfoRetriever;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Runs the real otelcol-contrib and node_exporter / windows_exporter on a local agent and checks what arrives at an
 * in-JVM OTLP/gRPC receiver. Local-only: {@code ./mvnw verify -Preal-binaries -Dtest=RealBinariesExportTest}.
 */
@Tag("real-binaries")
@WithJenkins
class RealBinariesExportTest {

    /** Holds the build inside {@code onMonit} until the test has seen what it waits for (see {@link HeldBuilds}). */
    private static final String BODY = HeldBuilds.HOLD;
    /**
     * Upper bound for start-up plus first export. Start-up alone (uploading and unpacking the binaries, starting
     * otelcol) took 19-25s on a WSL2 checkout under /mnt/c, and the plugin allows each process 1 minute to start.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final String INSECURE_TLS = "--set=exporters.otlp.tls.insecure=true";
    private static final String PLAINTEXT_TLS_BUG = "The built-in template's gRPC otlp exporter has no tls setting, so "
            + "otelcol 0.135.0 dials an http:// endpoint with TLS and every export fails with 'transport: "
            + "authentication handshake failed: tls: first record does not look like a TLS handshake'. The README "
            + "documents the workaround (" + INSECURE_TLS + " via ocAdditionalOptions or the global 'Otel-contrib "
            + "default additional options'), but out-of-the-box plaintext endpoints fail; maintainer to decide "
            + "whether the default template should handle http:// endpoints; see issue #124";

    @TempDir
    Path tmp;

    private OtlpMetricsReceiver receiver;
    private JenkinsRule jenkins;
    private DumbSlave agent;

    @BeforeAll
    static void installBinaries() throws Exception {
        RealBinaries.installResourceRoot(TestAgents.jenkinsHomeEnv());
    }

    @BeforeEach
    void startReceiver() throws Exception {
        receiver = new OtlpMetricsReceiver().start();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (agent != null) {
                // A failed test may leave its build held inside onMonit; release it so the plugin stops the processes.
                for (WorkflowJob job : jenkins.jenkins.getAllItems(WorkflowJob.class)) {
                    for (WorkflowRun run : job.getBuilds()) {
                        if (run.isBuilding()) {
                            HeldBuilds.release(job, agent);
                            jenkins.waitForCompletion(run);
                        }
                    }
                }
                String root = Path.of(agent.getRemoteFS()).toAbsolutePath().toString();
                Awaitility.await()
                        .atMost(Duration.ofSeconds(15))
                        .untilAsserted(() -> assertThat(Lifecycle.processesUnder(root))
                                .as("otelcol/exporter processes left running under " + root)
                                .isEmpty());
            }
        } finally {
            receiver.close();
        }
    }

    /**
     * 2s-scrape template with {@code tls.insecure} (see {@link #PLAINTEXT_TLS_BUG}), endpoint pointing at the
     * receiver, and an agent whose PATH is empty so the uploaded real binaries are used.
     */
    private void setUp(JenkinsRule j, String endpoint) throws Exception {
        FastScrapeTemplate.apply(FastScrapeTemplate.withIntervalAndInsecureTls("2s"));
        setUpTemplateUnchanged(j, endpoint);
    }

    /** Like {@link #setUp(JenkinsRule, String)} but leaves the configured template as it is. */
    private void setUpTemplateUnchanged(JenkinsRule j, String endpoint) throws Exception {
        jenkins = j;
        globalEnv(j, "OTEL_EXPORTER_OTLP_ENDPOINT", endpoint);
        Path empty = TestAgents.emptyDir(tmp);
        agent = TestAgents.agent(j, "t", empty, empty);
    }

    private void setUp(JenkinsRule j) throws Exception {
        setUp(j, receiver.endpoint());
    }

    private static WorkflowJob job(JenkinsRule j, String script) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "p");
        job.setDefinition(new CpsFlowDefinition(script, true));
        return job;
    }

    private static String onNode(String body) {
        return "node('t') { onMonit {\n" + body + "\n} }";
    }

    /**
     * Starts the held build, waits until {@code arrived} holds while the build is still inside {@code onMonit}, then
     * releases it and waits for it to succeed.
     */
    private WorkflowRun runAndAwait(
            JenkinsRule j, WorkflowJob job, Duration timeout, Function<WorkflowRun, Callable<Boolean>> arrived)
            throws Exception {
        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        Awaitility.await().atMost(timeout).until(arrived.apply(run));
        assertThat(run.isBuilding()).as("build still inside onMonit when the exports arrived").isTrue();
        HeldBuilds.release(job, agent);
        j.assertBuildStatusSuccess(j.waitForCompletion(run));
        return run;
    }

    /** {@link #runAndAwait} until the first export of the build arrived. */
    private WorkflowRun runAndAwaitExports(JenkinsRule j, WorkflowJob job) throws Exception {
        return runAndAwait(j, job, TIMEOUT, run -> () -> !receiver.requestsForRun(run.getId()).isEmpty());
    }

    /** {@link #runAndAwait} until the exports of the build contain all of {@code metrics}. */
    private WorkflowRun runAndAwaitMetrics(JenkinsRule j, WorkflowJob job, Duration timeout, Set<String> metrics)
            throws Exception {
        return runAndAwait(j, job, timeout, run -> () -> receiver.metricNames(run.getId()).containsAll(metrics));
    }

    private static List<Integer> ports(WorkflowRun run) throws Exception {
        return JenkinsFixtures.ports(JenkinsRule.getLog(run));
    }

    @Test
    void metricsArriveDuringBlock(JenkinsRule j) throws Exception {
        setUp(j);
        // runAndAwait checks the build is still inside the held block when the first export arrives.
        runAndAwaitExports(j, job(j, onNode(BODY)));
    }

    @Test
    void resourceAttributesMatchBuild(JenkinsRule j) throws Exception {
        setUp(j);
        Folder folder = j.createProject(Folder.class, "f");
        WorkflowJob job = folder.createProject(WorkflowJob.class, "p");
        job.setDefinition(new CpsFlowDefinition(onNode(BODY), true));
        WorkflowRun run = runAndAwaitExports(j, job);
        List<Integer> ports = ports(run);
        assertThat(ports).hasSize(1);

        List<Map<String, String>> resources = receiver.resourceAttributes(run.getId());
        assertThat(resources).isNotEmpty().anySatisfy(attrs -> {
            assertThat(attrs).containsEntry("cicd.pipeline.name", "f/p");
            assertThat(attrs).containsEntry("cicd.group.name", "f");
            assertThat(attrs).containsEntry("cicd.pipeline.run.id", run.getId());
            assertThat(attrs).containsEntry("cicd.controller.url", j.getURL().toString());
            assertThat(attrs.get("host.name")).isNotBlank();
            assertThat(attrs).containsEntry("cicd.worker.id", attrs.get("host.name") + ":" + ports.get(0));
        });
    }

    @Test
    void coreHostMetricsPresent(JenkinsRule j) throws Exception {
        setUp(j);
        Set<String> expected;
        if (RemoteComputerInfoRetriever.isWindows()) {
            expected = Set.of("windows_cpu_time_total", "windows_memory_physical_total_bytes");
        } else if ("darwin".equals(RemoteComputerInfoRetriever.getOs())) {
            expected = Set.of("node_cpu_seconds_total", "node_memory_total_bytes", "node_load1");
        } else {
            expected = Set.of("node_cpu_seconds_total", "node_memory_MemTotal_bytes", "node_load1");
        }
        WorkflowRun run = runAndAwaitMetrics(j, job(j, onNode(BODY)), TIMEOUT, expected);
        assertThat(receiver.metricNames(run.getId())).containsAll(expected);
    }

    @Test
    void noAuthorizationHeaderByDefault(JenkinsRule j) throws Exception {
        setUp(j);
        WorkflowRun run = runAndAwaitExports(j, job(j, onNode(BODY)));
        assertThat(receiver.headers(run.getId()))
                .isNotEmpty()
                .allSatisfy(h -> assertThat(h).doesNotContainKey("authorization"));
    }

    @Test
    void simpleAuthorizationHeaderOnWire(JenkinsRule j) throws Exception {
        setUp(j);
        globalEnv(j, "OTEL_EXPORTER_OTLP_HEADERS", "Authorization=Bearer test-token");
        WorkflowRun run = runAndAwaitExports(j, job(j, onNode(BODY)));
        assertThat(receiver.headers(run.getId()))
                .isNotEmpty()
                .allSatisfy(h -> assertThat(h).containsEntry("authorization", "Bearer test-token"));
    }

    @Test
    void headerValueWithYamlAndCollectorSpecialCharsOnWire(JenkinsRule j) throws Exception {
        setUp(j);
        // No "$$" here: Jenkins expands global env vars and turns "$$" into "$" before the plugin sees the value.
        String value = "Bearer it's \"x\" & y \\ $z ${env:HOME}";
        globalEnv(j, "OTEL_EXPORTER_OTLP_HEADERS", "Authorization=" + value);
        WorkflowRun run = runAndAwaitExports(j, job(j, onNode(BODY)));
        assertThat(receiver.headers(run.getId()))
                .isNotEmpty()
                .allSatisfy(h -> assertThat(h).containsEntry("authorization", value));
    }

    @Test
    void customHeaderKeyOnWire(JenkinsRule j) throws Exception {
        setUp(j);
        globalEnv(j, "OTEL_EXPORTER_OTLP_HEADERS", "X-Api-Key=abc");
        WorkflowRun run = runAndAwaitExports(j, job(j, onNode(BODY)));
        assertThat(receiver.headers(run.getId()))
                .isNotEmpty()
                .allSatisfy(h -> assertThat(h).containsEntry("x-api-key", "abc").doesNotContainKey("authorization"));
    }

    @Test
    void multipleHeadersOnWire(JenkinsRule j) throws Exception {
        setUp(j);
        globalEnv(j, "OTEL_EXPORTER_OTLP_HEADERS", "a=b,Authorization=Bearer x");
        WorkflowRun run = runAndAwaitExports(j, job(j, onNode(BODY)));
        assertThat(receiver.headers(run.getId()))
                .isNotEmpty()
                .allSatisfy(h -> assertThat(h).containsEntry("a", "b").containsEntry("authorization", "Bearer x"));
    }

    @Test
    void urlEncodedHeaderOnWire(JenkinsRule j) throws Exception {
        setUp(j);
        globalEnv(j, "OTEL_EXPORTER_OTLP_HEADERS", "Authorization=Basic%20abc");
        WorkflowRun run = runAndAwaitExports(j, job(j, onNode(BODY)));
        assertThat(receiver.headers(run.getId()))
                .isNotEmpty()
                .allSatisfy(h -> assertThat(h).containsEntry("authorization", "Basic abc"));
    }

    @Test
    void noExportsAfterBlockEnds(JenkinsRule j) throws Exception {
        setUp(j);
        WorkflowRun run = runAndAwaitExports(j, job(j, onNode(BODY)));
        int count = receiver.requestsForRun(run.getId()).size();
        Awaitility.await()
                .during(Duration.ofSeconds(6))
                .atMost(Duration.ofSeconds(8))
                .until(() -> receiver.requestsForRun(run.getId()).size() == count);
    }

    @Test
    void parallelBlocksProduceTwoWorkers(JenkinsRule j) throws Exception {
        setUp(j);
        String branch = "{ onMonit { " + BODY.replace("\n", "; ") + " } }";
        WorkflowJob job = job(j, "node('t') {\nparallel a: " + branch + ", b: " + branch + "\n}");
        // Both blocks stay held until both workers have exported, so the two collectors run at the same time.
        // onMonit start-up runs one branch after the other, hence twice the timeout.
        WorkflowRun run = runAndAwait(j, job, TIMEOUT.multipliedBy(2), r -> () -> workerIds(r.getId()).size() == 2);
        assertThat(workerIds(run.getId())).hasSize(2);
        List<Integer> ports = ports(run);
        assertThat(ports).hasSize(2).doesNotHaveDuplicates();
        Set<String> suffixes =
                workerIds(run.getId()).stream().map(w -> w.substring(w.lastIndexOf(':') + 1)).collect(Collectors.toSet());
        assertThat(suffixes).containsExactlyInAnyOrder(String.valueOf(ports.get(0)), String.valueOf(ports.get(1)));
    }

    private Set<String> workerIds(String runId) {
        return receiver.resourceAttributes(runId).stream()
                .map(a -> a.get("cicd.worker.id"))
                .filter(w -> w != null && !w.isBlank())
                .collect(Collectors.toSet());
    }

    /** A label added through a custom template in the global settings arrives on the exported resource. */
    @Test
    void customTemplateLabelOnWire(JenkinsRule j) throws Exception {
        FastScrapeTemplate.apply(FastScrapeTemplate.withResourceAttribute(
                FastScrapeTemplate.withIntervalAndInsecureTls("2s"), "test.custom.label", "custom-[[${env.BUILD_ID}]]"));
        setUpTemplateUnchanged(j, receiver.endpoint());
        WorkflowRun run = runAndAwaitExports(j, job(j, onNode(BODY)));
        assertThat(receiver.resourceAttributes(run.getId()))
                .isNotEmpty()
                .allSatisfy(attrs -> assertThat(attrs).containsEntry("test.custom.label", "custom-" + run.getId()));
    }

    @Test
    void endpointWithTrailingSlashDelivers(JenkinsRule j) throws Exception {
        setUp(j, receiver.endpoint() + "/");
        runAndAwaitExports(j, job(j, onNode(BODY)));
    }

    @Disabled(PLAINTEXT_TLS_BUG)
    @Test
    void plaintextHttpEndpointDelivers(JenkinsRule j) throws Exception {
        FastScrapeTemplate.apply(FastScrapeTemplate.withInterval("2s"));
        setUpTemplateUnchanged(j, receiver.endpoint());
        runAndAwaitExports(j, job(j, onNode(BODY)));
    }

    /**
     * The built-in template, loaded through its own code path, with its 15s scrape interval. Plaintext gRPC needs the
     * README's documented {@code tls.insecure} option, set here as the global default additional options.
     */
    @Test
    void builtInTemplateSmoke(JenkinsRule j) throws Exception {
        ONMonitConfig.get().setOcDefaultAdditionalOptions(INSECURE_TLS);
        setUpTemplateUnchanged(j, receiver.endpoint());
        assertThat(ONMonitConfig.get().getOtelConfigTemplate()).isEmpty();
        Folder folder = j.createProject(Folder.class, "f");
        WorkflowJob job = folder.createProject(WorkflowJob.class, "p");
        job.setDefinition(new CpsFlowDefinition(onNode(BODY), true));
        String cpu = RemoteComputerInfoRetriever.isWindows() ? "windows_cpu_time_total" : "node_cpu_seconds_total";
        // The built-in template scrapes every 15s, on top of start-up.
        WorkflowRun run = runAndAwaitMetrics(j, job, TIMEOUT.plusSeconds(30), Set.of(cpu));
        List<Integer> ports = ports(run);
        assertThat(ports).hasSize(1);

        assertThat(receiver.resourceAttributes(run.getId())).isNotEmpty().anySatisfy(attrs -> {
            assertThat(attrs).containsEntry("cicd.pipeline.name", "f/p");
            assertThat(attrs).containsEntry("cicd.group.name", "f");
            assertThat(attrs).containsEntry("cicd.controller.url", j.getURL().toString());
            assertThat(attrs.get("host.name")).isNotBlank();
            assertThat(attrs).containsEntry("cicd.worker.id", attrs.get("host.name") + ":" + ports.get(0));
        });
        assertThat(receiver.metricNames(run.getId())).contains(cpu);
    }
}
