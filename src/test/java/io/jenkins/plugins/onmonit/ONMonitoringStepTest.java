package io.jenkins.plugins.onmonit;

import static io.jenkins.plugins.onmonit.testing.JenkinsFixtures.globalEnv;
import static io.jenkins.plugins.onmonit.testing.JenkinsFixtures.ports;
import static io.jenkins.plugins.onmonit.testing.Lifecycle.assertAlive;
import static io.jenkins.plugins.onmonit.testing.Lifecycle.awaitDead;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.ExtensionList;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.slaves.DumbSlave;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import io.jenkins.plugins.onmonit.exec.DelegatedNodeExporterProcessFactory;
import io.jenkins.plugins.onmonit.exec.ExecRemoteNodeExporterProcessFactory;
import io.jenkins.plugins.onmonit.testing.DefaultPortLock;
import io.jenkins.plugins.onmonit.testing.FastScrapeTemplate;
import io.jenkins.plugins.onmonit.testing.HeldBuilds;
import io.jenkins.plugins.onmonit.testing.Marker;
import io.jenkins.plugins.onmonit.testing.RenderedConfig;
import io.jenkins.plugins.onmonit.testing.StandIn;
import io.jenkins.plugins.onmonit.testing.StandIns;
import io.jenkins.plugins.onmonit.testing.TestAgents;
import io.jenkins.plugins.onmonit.util.ComputerInfo;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@ExtendWith(DefaultPortLock.class)
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
        TestAgents.deleteResourceRoot();
    }

    @AfterEach
    void tearDown() {
        if (markerDir != null) {
            StandIns.killAll(markerDir);
            TestAgents.deleteResourceRoot();
        }
    }

    private DumbSlave agent(JenkinsRule j) throws Exception {
        return agent(j, 1);
    }

    private DumbSlave agent(JenkinsRule j, int executors) throws Exception {
        globalEnv(j, "OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317");
        return TestAgents.agent(j, "t", StandIns.create(tmp.resolve("bin")), markerDir, executors);
    }

    /** Like {@link #agent(JenkinsRule)} but without a global {@code OTEL_EXPORTER_OTLP_ENDPOINT}. */
    private DumbSlave agentWithoutEndpoint(JenkinsRule j) throws Exception {
        return TestAgents.agent(j, "t", StandIns.create(tmp.resolve("bin")), markerDir, 1);
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
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .until(() -> StandIns.markers(markerDir).size() >= count);
        return StandIns.markers(markerDir);
    }

    private List<Marker> markers(int expected) {
        List<Marker> markers = StandIns.markers(markerDir);
        assertThat(markers).hasSize(expected);
        return markers;
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
    void unsetEndpointWarnsInBuildLogAndUsesDefault(JenkinsRule j) throws Exception {
        agentWithoutEndpoint(j);
        WorkflowRun run = j.buildAndAssertSuccess(job(j, "p", onNode("echo 'x'")));
        j.assertLogContains("[on-monit] OTEL_EXPORTER_OTLP_ENDPOINT not set; using http://localhost:4317", run);
        assertThat(config(otelcol()).otlpEndpoint()).isEqualTo("http://localhost:4317");
    }

    @Test
    void unknownProtocolWarnsInBuildLog(JenkinsRule j) throws Exception {
        agent(j);
        globalEnv(j, "OTEL_EXPORTER_OTLP_PROTOCOL", "thrift");
        WorkflowRun run = j.buildAndAssertSuccess(job(j, "p", onNode("echo 'x'")));
        j.assertLogContains("[on-monit] Unsupported OTEL_EXPORTER_OTLP_PROTOCOL value 'thrift'; using grpc", run);
    }

    /** The README's old gRPC TLS workaround defines an orphan otlp exporter once the protocol selects otlphttp. */
    @Test
    void legacyOtlpOptionWithHttpProtocolWarns(JenkinsRule j) throws Exception {
        agent(j);
        globalEnv(j, "OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf");
        WorkflowRun run = j.buildAndAssertSuccess(
                job(j, "p", onNode("(ocAdditionalOptions: '--set=exporters.otlp.tls.insecure=true')", "echo 'x'")));
        j.assertLogContains(
                "[on-monit] WARNING: otel-contrib options set exporters.otlp.* but OTEL_EXPORTER_OTLP_PROTOCOL "
                        + "selects the otlphttp exporter",
                run);
    }

    /** A custom template that hard-codes the gRPC otlp exporter still needs the option, so it must not be told to drop it. */
    @Test
    void legacyOtlpOptionWithHardCodedOtlpTemplateDoesNotWarn(JenkinsRule j) throws Exception {
        FastScrapeTemplate.apply(FastScrapeTemplate.builtIn().replace("[(${otlpExporter})]", "otlp"));
        agent(j);
        globalEnv(j, "OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf");
        WorkflowRun run = j.buildAndAssertSuccess(
                job(j, "p", onNode("(ocAdditionalOptions: '--set=exporters.otlp.tls.insecure=true')", "echo 'x'")));
        assertThat(config(otelcol()).exporterNames()).contains("otlp");
        j.assertLogNotContains("WARNING: otel-contrib options", run);
    }

    @Test
    void endpointSetMeansNoWarning(JenkinsRule j) throws Exception {
        agent(j);
        WorkflowRun run = j.buildAndAssertSuccess(job(j, "p", onNode("echo 'x'")));
        j.assertLogNotContains("not set; using", run);
        j.assertLogNotContains("WARNING: otel-contrib options", run);
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
    void withEnvEndpointAndHeadersReachRenderedConfig(JenkinsRule j) throws Exception {
        TestAgents.agent(j, "t", StandIns.create(tmp.resolve("bin")), markerDir);
        WorkflowJob job = job(
                j,
                "p",
                "node('t') { withEnv(['OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317', "
                        + "'OTEL_EXPORTER_OTLP_HEADERS=Authorization=Bearer test-token']) { onMonit { echo 'x' } } }");
        j.buildAndAssertSuccess(job);
        RenderedConfig cfg = config(otelcol());
        assertThat(cfg.otlpEndpoint()).isEqualTo("http://localhost:4317");
        assertThat(cfg.otlpHeaders()).isEqualTo(Map.of("Authorization", "Bearer test-token"));
    }

    @Test
    void agentNodePropertyEndpointAndHeadersReachRenderedConfig(JenkinsRule j) throws Exception {
        DumbSlave agent = TestAgents.agent(j, "t", StandIns.create(tmp.resolve("bin")), markerDir);
        agent.getNodeProperties()
                .add(new EnvironmentVariablesNodeProperty(
                        new EnvironmentVariablesNodeProperty.Entry(
                                "OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317"),
                        new EnvironmentVariablesNodeProperty.Entry(
                                "OTEL_EXPORTER_OTLP_HEADERS", "Authorization=Bearer test-token")));
        j.buildAndAssertSuccess(job(j, "p", onNode("echo 'x'")));
        RenderedConfig cfg = config(otelcol());
        assertThat(cfg.otlpEndpoint()).isEqualTo("http://localhost:4317");
        assertThat(cfg.otlpHeaders()).isEqualTo(Map.of("Authorization", "Bearer test-token"));
    }

    @Test
    void withEnvOverridesGlobalEndpoint(JenkinsRule j) throws Exception {
        agent(j);
        j.buildAndAssertSuccess(job(
                j,
                "p",
                "node('t') { withEnv(['OTEL_EXPORTER_OTLP_ENDPOINT=http://override:4317']) "
                        + "{ onMonit { echo 'x' } } }"));
        assertThat(config(otelcol()).otlpEndpoint()).isEqualTo("http://override:4317");
    }

    @Test
    void declarativeEnvironmentEndpointReachesRenderedConfig(JenkinsRule j) throws Exception {
        TestAgents.agent(j, "t", StandIns.create(tmp.resolve("bin")), markerDir);
        WorkflowJob job = job(
                j,
                "p",
                "pipeline {\n  agent { label 't' }\n"
                        + "  environment {\n"
                        + "    OTEL_EXPORTER_OTLP_ENDPOINT = 'http://localhost:4317'\n"
                        + "    OTEL_EXPORTER_OTLP_HEADERS = 'Authorization=Bearer test-token'\n"
                        + "  }\n"
                        + "  options { onMonit() }\n"
                        + "  stages { stage('s') { steps { echo 'x' } } }\n}");
        j.buildAndAssertSuccess(job);
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
        WorkflowJob job = job(j, "p", "node('t') {\n" + "parallel a: " + branch + ", b: " + branch + "\n}");
        WorkflowRun run = j.buildAndAssertSuccess(job);
        List<Integer> ports = ports(JenkinsRule.getLog(run));
        assertThat(ports).hasSize(2).doesNotHaveDuplicates();
        List<Marker> markers = StandIns.markers(markerDir);
        assertThat(markers)
                .extracting(Marker::role)
                .containsExactlyInAnyOrder(
                        StandIn.Role.OTELCOL, StandIn.Role.OTELCOL, StandIns.exporterRole(), StandIns.exporterRole());
        awaitDead(markers, DEAD);
    }

    /** Step arguments that make the otelcol stand-in of this {@code onMonit} wait for {@code gate} before it is ready. */
    private static String gated(Path gate) {
        String arg = StandIn.GATE_FILE_ARG + gate.toAbsolutePath().toString().replace('\\', '/');
        return "(ocAdditionalOptions: '\"" + arg + "\"')";
    }

    /**
     * While one {@code parallel} branch is still waiting for its collector to become ready, another branch starts
     * its own processes and runs its body (#126): starting the processes must not block the pipeline.
     */
    @Test
    void parallelStartupDoesNotSerialize(JenkinsRule j) throws Exception {
        agent(j);
        Path gate = tmp.resolve("gate");
        WorkflowJob job = job(
                j,
                "p",
                "node('t') {\n"
                        + "parallel a: { onMonit" + gated(gate) + " { echo 'a-body' } }, "
                        + "b: { onMonit { echo 'b-body' } }\n}");
        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        try {
            try {
                Awaitility.await()
                        .atMost(Duration.ofSeconds(45))
                        .until(() -> JenkinsRule.getLog(run).contains("b-body"));
            } catch (ConditionTimeoutException e) {
                throw new AssertionError(
                        "Branch b did not run its body while branch a was starting; build log:\n"
                                + JenkinsRule.getLog(run),
                        e);
            }
            assertThat(JenkinsRule.getLog(run)).doesNotContain("a-body");
        } finally {
            Files.writeString(gate, "open");
        }
        j.assertBuildStatusSuccess(j.waitForCompletion(run));
        j.assertLogContains("a-body", run);
        List<Marker> markers = StandIns.markers(markerDir);
        assertThat(markers).hasSize(4);
        awaitDead(markers, DEAD);
    }

    /** Aborting the build while the collector is still starting kills the collector and node_exporter. */
    @Test
    void abortDuringStartupKillsProcesses(JenkinsRule j) throws Exception {
        agent(j);
        Path gate = tmp.resolve("gate");
        WorkflowRun run = job(j, "p", onNode(gated(gate), "echo 'body-ran'"))
                .scheduleBuild2(0)
                .waitForStart();
        List<Marker> markers = awaitMarkers(2);
        assertThat(markers)
                .extracting(Marker::role)
                .containsExactlyInAnyOrder(StandIn.Role.OTELCOL, StandIns.exporterRole());
        assertAlive(markers);
        run.doStop();
        try {
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> !run.isBuilding());
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("Build still running 30s after doStop; build log:\n" + JenkinsRule.getLog(run), e);
        }
        j.assertBuildStatus(Result.ABORTED, run);
        j.assertLogNotContains("body-ran", run);
        awaitDead(markers, DEAD);
    }

    @Test
    void declarativeOptionsWorks(JenkinsRule j) throws Exception {
        agent(j);
        WorkflowJob job = job(
                j,
                "p",
                "pipeline {\n  agent { label 't' }\n"
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
        try {
            awaitMarkers(2);
            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertAlive(markers(2)));
        } finally {
            HeldBuilds.releaseOn(agent, job);
        }
        j.assertBuildStatusSuccess(j.waitForCompletion(run));
        awaitDead(StandIns.markers(markerDir), DEAD);
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
        } catch (IOException e) {
            return Assumptions.abort("port " + port + " is not available: " + e);
        }
    }

    /** #142: another process takes node_exporter's port after the plugin has checked that it is free. */
    @Test
    void portTakenAfterCheckIsRetried(JenkinsRule j) throws Exception {
        agent(j);
        TakePortOnStart factory = new TakePortOnStart();
        // ExtensionList.add(int, T) ignores the index, so remove the real factory instead of relying on the order.
        // JenkinsRule creates a fresh Jenkins for each test, so nothing needs restoring.
        ExtensionList<RemoteNodeExporterProcessFactory> factories =
                ExtensionList.lookup(RemoteNodeExporterProcessFactory.class);
        factories.remove(factories.get(DelegatedNodeExporterProcessFactory.class));
        factories.add(factory);
        assertThat(factories).as("test factory must be the only node_exporter factory").containsExactly(factory);
        try {
            WorkflowRun run = j.buildAndAssertSuccess(job(j, "p", onNode("echo 'x'")));
            int taken = factory.taken.getLocalPort();
            j.assertLogContains("[on-monit] port " + taken + " taken, retrying", run);
            Marker exporter = StandIns.markers(markerDir).stream()
                    .filter(m -> m.role() == StandIns.exporterRole())
                    .findFirst()
                    .orElseThrow();
            assertThat(exporter.port()).isNotEqualTo(taken);
            assertThat(exporter.args())
                    .filteredOn(a -> a.startsWith("--web.listen-address="))
                    .containsExactly("--web.listen-address=:" + exporter.port());
            assertThat(config(otelcol()).scrapeTarget()).isEqualTo("localhost:" + exporter.port());
        } finally {
            if (factory.taken != null) {
                factory.taken.close();
            }
        }
    }

    /** Starts node_exporter like the exec factory, but first binds the port it is given, the first time only. */
    private static final class TakePortOnStart extends RemoteNodeExporterProcessFactory {

        private final ExecRemoteNodeExporterProcessFactory delegate = new ExecRemoteNodeExporterProcessFactory();

        volatile ServerSocket taken;

        @Override
        public String getDisplayName() {
            return "Exec node_exporter, port taken on first start";
        }

        @Override
        public boolean isSupported(Launcher launcher, TaskListener listener, ComputerInfo info) {
            return delegate.isSupported(launcher, listener, info);
        }

        @Override
        public RemoteNodeExporterProcess create(
                LauncherProvider launcherProvider,
                TaskListener listener,
                ComputerInfo info,
                FilePath temp,
                String envCookie,
                String additionalOptions,
                boolean debug)
                throws Throwable {
            RemoteNodeExporterProcess process =
                    delegate.create(launcherProvider, listener, info, temp, envCookie, additionalOptions, debug);
            return new RemoteNodeExporterProcess() {
                @Override
                public void start(TaskListener listener, int port) throws IOException, InterruptedException {
                    if (taken == null) {
                        taken = new ServerSocket(port);
                    }
                    process.start(listener, port);
                }

                @Override
                public void stop(TaskListener listener) throws IOException, InterruptedException {
                    process.stop(listener);
                }
            };
        }
    }

    @Test
    void jobNameWithSpaceWorks(JenkinsRule j) throws Exception {
        agent(j);
        j.buildAndAssertSuccess(job(j, "with space", onNode("echo 'x'")));
        Marker oc = otelcol();
        assertThat(oc.args())
                .anySatisfy(a -> assertThat(a).startsWith("--config=file:").contains("with space"));
        assertThat(oc.configCopy()).exists();
        assertThat(Files.size(oc.configCopy())).isPositive();
    }

    @Test
    void concurrentJobsOnSameAgentUseDistinctPorts(JenkinsRule j) throws Exception {
        DumbSlave agent = agent(j, 2);
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
            HeldBuilds.releaseOn(agent, job1);
            HeldBuilds.releaseOn(agent, job2);
        }
        j.assertBuildStatusSuccess(j.waitForCompletion(run1));
        j.assertBuildStatusSuccess(j.waitForCompletion(run2));
        assertThat(markers).hasSize(4);
        awaitDead(StandIns.markers(markerDir), DEAD);
    }
}
