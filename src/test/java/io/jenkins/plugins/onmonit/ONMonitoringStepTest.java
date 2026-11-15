package io.jenkins.plugins.onmonit;

import static org.assertj.core.api.Assertions.assertThat;
import static io.jenkins.plugins.onmonit.testing.JenkinsFixtures.globalEnv;
import static io.jenkins.plugins.onmonit.testing.JenkinsFixtures.ports;
import static io.jenkins.plugins.onmonit.testing.Lifecycle.assertAlive;
import static io.jenkins.plugins.onmonit.testing.Lifecycle.awaitDead;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.Result;
import hudson.slaves.DumbSlave;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import io.jenkins.plugins.onmonit.testing.FastScrapeTemplate;
import io.jenkins.plugins.onmonit.testing.HeldBuilds;
import io.jenkins.plugins.onmonit.testing.Marker;
import io.jenkins.plugins.onmonit.testing.RenderedConfig;
import io.jenkins.plugins.onmonit.testing.StandIn;
import io.jenkins.plugins.onmonit.testing.StandIns;
import io.jenkins.plugins.onmonit.testing.TestAgents;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ONMonitoringStepTest {

    private static final Duration DEAD = Duration.ofSeconds(15);

    @TempDir
    Path tmp;

    private Path markerDir;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(StandIns.supported());
        markerDir = Files.createDirectories(tmp.resolve("markers"));
        FileUtils.deleteDirectory(TestAgents.jenkinsHomeEnv().resolve("opentelemetry-agent-metrics").toFile());
    }

    @AfterEach
    void tearDown() {
        if (markerDir != null) {
            StandIns.killAll(markerDir);
        }
    }

    private DumbSlave agent(JenkinsRule j) throws Exception {
        globalEnv(j, "OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317");
        return TestAgents.agent(j, "t", StandIns.create(tmp.resolve("bin")), markerDir);
    }

    private static WorkflowJob job(JenkinsRule j, String name, String script) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(script, true));
        return job;
    }

    private static String onNode(String stepArgs, String body) {
        return "node('t') { onMonit" + stepArgs + " {\n" + body + "\n} }";
    }

    private static String onNode(String body) {
        return onNode("", body);
    }

    private List<Marker> awaitMarkers(int count) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> StandIns.markers(markerDir).size() >= count);
        return StandIns.markers(markerDir);
    }

    private static RenderedConfig config(Marker otelcol) throws Exception {
        return RenderedConfig.parse(Files.readString(otelcol.configCopy()));
    }

    private Marker otelcol() {
        return StandIns.markers(markerDir).stream()
                .filter(m -> m.role() == StandIn.Role.OTELCOL)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void launchCollectorFalseStartsNothing(JenkinsRule j) throws Exception {
        agent(j);
        WorkflowJob job = job(j, "p", onNode("(launchCollector: false)", "echo 'x'"));
        WorkflowRun run = j.buildAndAssertSuccess(job);
        j.assertLogContains("[on-monit] Skipping launch of node_exporter and otel-contrib processes.", run);
        assertThat(StandIns.markers(markerDir)).isEmpty();
        assertThat(run.getAction(MonitoringAction.class)).isNotNull();
    }

    @Test
    void renderedConfigMatchesBuild(JenkinsRule j) throws Exception {
        agent(j);
        Folder folder = j.createProject(Folder.class, "f");
        WorkflowJob job = folder.createProject(WorkflowJob.class, "p");
        job.setDefinition(new CpsFlowDefinition(onNode("echo 'x'"), true));
        WorkflowRun run = j.buildAndAssertSuccess(job);
        List<Integer> ports = ports(JenkinsRule.getLog(run));
        assertThat(ports).hasSize(1);
        RenderedConfig cfg = config(otelcol());
        assertThat(cfg.scrapeTarget()).isEqualTo("localhost:" + ports.get(0));
        assertThat(cfg.resourceAttribute("cicd.pipeline.name")).isEqualTo("f/p");
        assertThat(cfg.resourceAttribute("cicd.group.name")).isEqualTo("f");
        assertThat(cfg.resourceAttribute("cicd.pipeline.run.id")).isEqualTo("1");
        assertThat(cfg.otlpEndpoint()).isEqualTo("http://localhost:4317");
    }

    /** A custom template from the global settings is used, and its template expressions are rendered. */
    @Test
    void customGlobalTemplateLabelReachesRenderedConfig(JenkinsRule j) throws Exception {
        agent(j);
        FastScrapeTemplate.apply(FastScrapeTemplate.withResourceAttribute(
                FastScrapeTemplate.builtIn(), "test.custom.label", "custom-[[${env.BUILD_ID}]]"));
        j.buildAndAssertSuccess(job(j, "p", onNode("echo 'x'")));
        RenderedConfig cfg = config(otelcol());
        assertThat(cfg.resourceAttribute("test.custom.label")).isEqualTo("custom-1");
        assertThat(cfg.resourceAttribute("cicd.pipeline.name")).isEqualTo("p");
    }

    @Test
    void authHeaderReachesRenderedConfig(JenkinsRule j) throws Exception {
        agent(j);
        globalEnv(j, "OTEL_EXPORTER_OTLP_HEADERS", "Authorization=Bearer test-token");
        WorkflowJob job = job(j, "p", onNode("echo 'x'"));
        j.buildAndAssertSuccess(job);
        assertThat(config(otelcol()).otlpHeaders()).isEqualTo(Map.of("Authorization", "Bearer test-token"));
    }

    @Test
    @Disabled("ONMonitoringStepExecution reads Run.getEnvironment(), ignoring withEnv/environment{} OTEL_* variables; "
            + "it should read the step context's EnvVars; see issue #123")
    void withEnvEndpointAndHeadersReachRenderedConfig(JenkinsRule j) throws Exception {
        TestAgents.agent(j, "t", StandIns.create(tmp.resolve("bin")), markerDir);
        WorkflowJob job = job(j, "p", "node('t') { withEnv(['OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317', "
                + "'OTEL_EXPORTER_OTLP_HEADERS=Authorization=Bearer test-token']) { onMonit { echo 'x' } } }");
        j.buildAndAssertSuccess(job);
        RenderedConfig cfg = config(otelcol());
        assertThat(cfg.otlpEndpoint()).isEqualTo("http://localhost:4317");
        assertThat(cfg.otlpHeaders()).isEqualTo(Map.of("Authorization", "Bearer test-token"));
    }

    @Test
    @Disabled("ONMonitoringStepExecution reads Run.getEnvironment(), which does not include the agent's node-property "
            + "environment variables; it should read the step context's EnvVars; see issue #123")
    void agentNodePropertyEndpointAndHeadersReachRenderedConfig(JenkinsRule j) throws Exception {
        DumbSlave agent = TestAgents.agent(j, "t", StandIns.create(tmp.resolve("bin")), markerDir);
        agent.getNodeProperties().add(new EnvironmentVariablesNodeProperty(
                new EnvironmentVariablesNodeProperty.Entry("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317"),
                new EnvironmentVariablesNodeProperty.Entry("OTEL_EXPORTER_OTLP_HEADERS", "Authorization=Bearer test-token")));
        j.buildAndAssertSuccess(job(j, "p", onNode("echo 'x'")));
        RenderedConfig cfg = config(otelcol());
        assertThat(cfg.otlpEndpoint()).isEqualTo("http://localhost:4317");
        assertThat(cfg.otlpHeaders()).isEqualTo(Map.of("Authorization", "Bearer test-token"));
    }

    @Test
    void noAuthHeaderInRenderedConfig(JenkinsRule j) throws Exception {
        agent(j);
        j.buildAndAssertSuccess(job(j, "p", onNode("echo 'x'")));
        assertThat(config(otelcol()).otlpHeaders()).isEmpty();
    }

    @Test
    void parallelOnMonitUsesDistinctPorts(JenkinsRule j) throws Exception {
        agent(j);
        String branch = "{ " + "onMonit { sleep 2 } }";
        WorkflowJob job = job(j, "p", "node('t') {\n"
                + "parallel a: " + branch + ", b: " + branch + "\n}");
        WorkflowRun run = j.buildAndAssertSuccess(job);
        List<Integer> ports = ports(JenkinsRule.getLog(run));
        assertThat(ports).hasSize(2).doesNotHaveDuplicates();
        List<Marker> markers = StandIns.markers(markerDir);
        assertThat(markers).extracting(Marker::role)
                .containsExactlyInAnyOrder(StandIn.Role.OTELCOL, StandIn.Role.OTELCOL, StandIns.exporterRole(), StandIns.exporterRole());
        awaitDead(markers, DEAD);
    }

    @Test
    void declarativeOptionsWorks(JenkinsRule j) throws Exception {
        agent(j);
        WorkflowJob job = job(j, "p", "pipeline {\n  agent { label 't' }\n"
                + "  options { onMonit() }\n"
                + "  stages { stage('s') { steps { echo 'x' } } }\n}");
        WorkflowRun run = j.buildAndAssertSuccess(job);
        j.assertLogContains("node_exporter + otel-contrib started", run);
        List<Marker> markers = StandIns.markers(markerDir);
        assertThat(markers).hasSize(2);
        awaitDead(markers, DEAD);
    }

    @Test
    void blockFormProcessesAliveInsideBody(JenkinsRule j) throws Exception {
        DumbSlave agent = agent(j);
        WorkflowJob job = job(j, "p", onNode(HeldBuilds.HOLD));
        WorkflowRun run = HeldBuilds.hold(j, job);
        List<Marker> markers;
        try {
            markers = awaitMarkers(2);
            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertAlive(markers(2)));
        } finally {
            HeldBuilds.release(job, agent);
        }
        j.assertBuildStatusSuccess(j.waitForCompletion(run));
        awaitDead(StandIns.markers(markerDir), DEAD);
    }

    private List<Marker> markers(int expected) {
        List<Marker> markers = StandIns.markers(markerDir);
        assertThat(markers).hasSize(expected);
        return markers;
    }

    @Test
    void debugPrintsConfigAndProcessOutput(JenkinsRule j) throws Exception {
        agent(j);
        WorkflowRun run = j.buildAndAssertSuccess(job(j, "p", onNode("(debug: true)", "echo 'x'")));
        j.assertLogContains("[on-monit] otel-collector config:", run);
        j.assertLogContains("Everything is ready.", run);
    }

    @Test
    void withoutDebugNoConfigInLog(JenkinsRule j) throws Exception {
        agent(j);
        WorkflowRun run = j.buildAndAssertSuccess(job(j, "p", onNode("echo 'x'")));
        j.assertLogNotContains("[on-monit] otel-collector config:", run);
        j.assertLogNotContains("Everything is ready.", run);
    }

    /**
     * The body sleeps instead of using {@link HeldBuilds#HOLD}: with {@code waitUntil} the abort was sometimes lost
     * (2 of 5 local runs). The log then showed "Click here to forcibly terminate running steps" followed by more
     * {@code waitUntil} retries, and the build never ended. {@code sleep} is the innermost step for the whole wait,
     * so {@code doStop} reaches it and the abort propagates through {@code onMonit}.
     */
    @Test
    void abortInsideBlockStopsProcesses(JenkinsRule j) throws Exception {
        agent(j);
        WorkflowJob job = job(j, "p", onNode("echo 'inside'\nsleep 600"));
        WorkflowRun run = HeldBuilds.hold(j, job);
        j.waitForMessage("Sleeping for", run);
        List<Marker> markers = awaitMarkers(2);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertAlive(markers(2)));
        run.doStop();
        try {
            Awaitility.await().atMost(Duration.ofSeconds(90)).until(() -> !run.isBuilding());
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("Build still running 90s after doStop; build log:\n" + JenkinsRule.getLog(run), e);
        }
        j.assertBuildStatus(Result.ABORTED, run);
        awaitDead(markers, DEAD);
    }

    @Test
    void occupiedDefaultPortIsSkipped(JenkinsRule j) throws Exception {
        agent(j);
        try (ServerSocket occupied = bind(9100)) {
            WorkflowRun run = j.buildAndAssertSuccess(job(j, "p", onNode("echo 'x'")));
            List<Integer> ports = ports(JenkinsRule.getLog(run));
            assertThat(ports).hasSize(1);
            assertThat(ports.get(0)).isNotEqualTo(9100);
            assertThat(config(otelcol()).scrapeTarget()).isEqualTo("localhost:" + ports.get(0));
            assertThat(occupied.isClosed()).isFalse();
        }
    }

    private static ServerSocket bind(int port) {
        try {
            return new ServerSocket(port);
        } catch (java.io.IOException e) {
            assumeTrue(false, "port " + port + " is not available: " + e);
            return null;
        }
    }

    @Test
    void jobNameWithSpaceWorks(JenkinsRule j) throws Exception {
        agent(j);
        j.buildAndAssertSuccess(job(j, "with space", onNode("echo 'x'")));
        Marker oc = otelcol();
        assertThat(oc.args()).anySatisfy(a -> assertThat(a).startsWith("--config=file:").contains("with space"));
        assertThat(oc.configCopy()).exists();
        assertThat(Files.size(oc.configCopy())).isPositive();
    }

    @Test
    void concurrentJobsOnSameAgentUseDistinctPorts(JenkinsRule j) throws Exception {
        DumbSlave agent = TestAgents.agent(j, "t", StandIns.create(tmp.resolve("bin")), markerDir, 2);
        globalEnv(j, "OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317");
        WorkflowJob job1 = job(j, "p1", onNode(HeldBuilds.HOLD));
        WorkflowJob job2 = job(j, "p2", onNode(HeldBuilds.HOLD));
        WorkflowRun run1 = HeldBuilds.hold(j, job1);
        WorkflowRun run2 = HeldBuilds.hold(j, job2);
        List<Marker> markers;
        try {
            markers = awaitMarkers(4);
            List<Integer> ports = new ArrayList<>(ports(JenkinsRule.getLog(run1)));
            ports.addAll(ports(JenkinsRule.getLog(run2)));
            assertThat(ports).hasSize(2).doesNotHaveDuplicates();
        } finally {
            HeldBuilds.release(job1, agent);
            HeldBuilds.release(job2, agent);
        }
        j.assertBuildStatusSuccess(j.waitForCompletion(run1));
        j.assertBuildStatusSuccess(j.waitForCompletion(run2));
        assertThat(markers).hasSize(4);
        awaitDead(StandIns.markers(markerDir), DEAD);
    }
}
