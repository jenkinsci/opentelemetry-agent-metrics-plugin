package io.jenkins.plugins.onmonit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import hudson.model.Result;
import io.jenkins.plugins.onmonit.testing.DownloadServer;
import io.jenkins.plugins.onmonit.testing.Marker;
import io.jenkins.plugins.onmonit.testing.StandIn;
import io.jenkins.plugins.onmonit.testing.StandIns;
import io.jenkins.plugins.onmonit.testing.TestAgents;
import io.jenkins.plugins.onmonit.util.RemoteComputerInfoRetriever;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.awaitility.Awaitility;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class BinarySourcingTest {

    private static final String PIPELINE =
            "node('t') { onMonit { echo 'inside' } }";

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

    private WorkflowRun build(JenkinsRule j, Path pathDir, Result expected) throws Exception {
        TestAgents.agent(j, "t", pathDir, markerDir);
        WorkflowJob job = j.createProject(WorkflowJob.class, "p");
        job.setDefinition(new CpsFlowDefinition(PIPELINE, true));
        return j.assertBuildStatus(expected, job.scheduleBuild2(0));
    }

    private WorkflowRun buildOnPath(JenkinsRule j) throws Exception {
        return build(j, StandIns.create(tmp.resolve("bin")), Result.SUCCESS);
    }

    private static String exporterBinary() {
        return RemoteComputerInfoRetriever.isWindows() ? "windows_exporter" : "node_exporter";
    }

    @Test
    void usesBinariesOnPath(JenkinsRule j) throws Exception {
        WorkflowRun run = buildOnPath(j);
        j.assertLogContains("Exec otelcol-contrib (binary otelcol-contrib present on remote machine)", run);
        j.assertLogContains("Exec node_exporter (binary node_exporter present on remote machine)", run);
        j.assertLogContains("node_exporter + otel-contrib started", run);
        j.assertLogContains("node_exporter + otel-contrib stopped", run);
        List<Marker> markers = StandIns.markers(markerDir);
        assertThat(markers).extracting(Marker::role).containsExactlyInAnyOrder(
                StandIn.Role.OTELCOL,
                RemoteComputerInfoRetriever.isWindows() ? StandIn.Role.WINDOWS_EXPORTER : StandIn.Role.NODE_EXPORTER);
    }

    @Test
    void downloadsWhenBaseUrlConfigured(JenkinsRule j) throws Exception {
        String os = RemoteComputerInfoRetriever.getOs();
        boolean amd64 = RemoteComputerInfoRetriever.isAmd64();
        String ne = "/" + ResourceUtil.getNodeExporterFilename(os, amd64);
        String oc = "/" + ResourceUtil.getOtelFilename(os, amd64);
        boolean win = RemoteComputerInfoRetriever.isWindows();
        try (DownloadServer server = DownloadServer.start(Map.of(
                ne, StandIns.bytesFor(win ? StandIn.Role.WINDOWS_EXPORTER : StandIn.Role.NODE_EXPORTER),
                oc, StandIns.bytesFor(StandIn.Role.OTELCOL)))) {
            ONMonitConfig.get().setDownloadBaseUrl(server.baseUrl());
            WorkflowRun run = build(j, TestAgents.emptyDir(tmp), Result.SUCCESS);
            j.assertLogContains("(download otelcol-contrib from web to remote machine)", run);
            assertThat(server.requestedPaths()).containsExactlyInAnyOrder(ne, oc);
        }
    }

    @Test
    void uploadsFromJenkinsHome(JenkinsRule j) throws Exception {
        StandIns.writeResourceRoot(TestAgents.jenkinsHomeEnv());
        WorkflowRun run = build(j, TestAgents.emptyDir(tmp), Result.SUCCESS);
        j.assertLogContains("(uploaded otelcol-contrib from master to remote machine)", run);
    }

    @Test
    void fallsBackToUploadWhenDownloadFails(JenkinsRule j) throws Exception {
        StandIns.writeResourceRoot(TestAgents.jenkinsHomeEnv());
        try (DownloadServer server = DownloadServer.start(Map.of())) {
            ONMonitConfig.get().setDownloadBaseUrl(server.baseUrl());
            WorkflowRun run = build(j, TestAgents.emptyDir(tmp), Result.SUCCESS);
            j.assertLogContains("(uploaded otelcol-contrib from master to remote machine)", run);
        }
    }

    @Test
    void failsClearlyWhenNothingAvailable(JenkinsRule j) throws Exception {
        WorkflowRun run = build(j, TestAgents.emptyDir(tmp), Result.FAILURE);
        String log = JenkinsRule.getLog(run);
        assertThat(log).containsAnyOf(
                "Could not find a suitable node_exporter provider",
                "Could not find a suitable otel-contrib provider");
    }

    @Test
    void processesAreGoneAfterBlock(JenkinsRule j) throws Exception {
        buildOnPath(j);
        List<Marker> markers = StandIns.markers(markerDir);
        assertThat(markers).hasSize(2);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            for (Marker m : markers) {
                assertThat(ProcessHandle.of(m.pid()).map(ProcessHandle::isAlive).orElse(false))
                        .as("pid %d of %s alive", m.pid(), m.role()).isFalse();
                if (m.parentPid() > 0) {
                    assertThat(ProcessHandle.of(m.parentPid()).map(ProcessHandle::isAlive).orElse(false))
                            .as("parent pid %d of %s alive", m.parentPid(), m.role()).isFalse();
                }
            }
        });
    }
}
