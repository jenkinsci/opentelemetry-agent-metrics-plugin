package io.jenkins.plugins.onmonit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import hudson.FilePath;
import hudson.model.Result;
import hudson.slaves.DumbSlave;
import io.jenkins.plugins.onmonit.testing.DownloadServer;
import io.jenkins.plugins.onmonit.testing.Lifecycle;
import io.jenkins.plugins.onmonit.testing.Marker;
import io.jenkins.plugins.onmonit.testing.StandIn;
import io.jenkins.plugins.onmonit.testing.StandIns;
import io.jenkins.plugins.onmonit.testing.TestAgents;
import io.jenkins.plugins.onmonit.util.BinaryCache;
import io.jenkins.plugins.onmonit.util.RemoteComputerInfoRetriever;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
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
        TestAgents.deleteResourceRoot();
    }

    @AfterEach
    void tearDown() {
        if (markerDir != null) {
            StandIns.killAll(markerDir);
            TestAgents.deleteResourceRoot();
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
                StandIn.Role.OTELCOL, StandIns.exporterRole());
    }

    @Test
    void downloadsWhenBaseUrlConfigured(JenkinsRule j) throws Exception {
        String os = RemoteComputerInfoRetriever.getOs();
        boolean amd64 = RemoteComputerInfoRetriever.isAmd64();
        String ne = "/" + ResourceUtil.getNodeExporterFilename(os, amd64);
        String oc = "/" + ResourceUtil.getOtelFilename(os, amd64);
        try (DownloadServer server = DownloadServer.start(Map.of(
                ne, StandIns.bytesFor(StandIns.exporterRole()),
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
            assertThat(server.requestedPaths()).isNotEmpty();
        }
    }

    @Test
    void failsClearlyWhenNothingAvailable(JenkinsRule j) throws Exception {
        WorkflowRun run = build(j, TestAgents.emptyDir(tmp), Result.FAILURE);
        // The step looks for node_exporter first, so that is the provider it reports missing.
        j.assertLogContains("[on-monit] FATAL: Could not find a suitable node_exporter provider", run);
    }

    @Test
    void processesAreGoneAfterBlock(JenkinsRule j) throws Exception {
        buildOnPath(j);
        List<Marker> markers = StandIns.markers(markerDir);
        assertThat(markers).hasSize(2);
        Lifecycle.awaitDead(markers, Duration.ofSeconds(10));
    }

    private static Path cacheRoot(DumbSlave agent) {
        FilePath root = agent.getRootPath();
        assertThat(root).isNotNull();
        return Path.of(root.getRemote()).resolve("caches").resolve("opentelemetry-agent-metrics");
    }

    /** The cached files named {@code filename} under any key directory of the agent's cache. */
    private static List<Path> cached(DumbSlave agent, String filename) throws Exception {
        Path root = cacheRoot(agent);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> keys = Files.list(root)) {
            return keys.map(k -> k.resolve(filename)).filter(Files::exists).toList();
        }
    }

    private static List<Path> leftoverTmpFiles(DumbSlave agent) throws Exception {
        Path root = cacheRoot(agent);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".tmp")).toList();
        }
    }

    private static String otelFilename() {
        return ResourceUtil.getOtelFilename(RemoteComputerInfoRetriever.getOs(), RemoteComputerInfoRetriever.isAmd64());
    }

    private static String exporterFilename() {
        return ResourceUtil.getNodeExporterFilename(
                RemoteComputerInfoRetriever.getOs(), RemoteComputerInfoRetriever.isAmd64());
    }

    private static WorkflowJob job(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "p");
        job.setDefinition(new CpsFlowDefinition(PIPELINE, true));
        return job;
    }

    @Test
    void uploadCachesBinariesOnAgent(JenkinsRule j) throws Exception {
        StandIns.writeResourceRoot(TestAgents.jenkinsHomeEnv());
        DumbSlave agent = TestAgents.agent(j, "t", TestAgents.emptyDir(tmp), markerDir);
        WorkflowJob job = job(j);

        WorkflowRun first = j.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        j.assertLogContains("(uploaded otelcol-contrib from master to remote machine)", first);
        j.assertLogContains("[on-monit] Caching otelcol-contrib in ", first);
        j.assertLogContains("[on-monit] Caching " + exporterBinary() + " in ", first);
        List<Path> otel = cached(agent, otelFilename());
        List<Path> exporter = cached(agent, exporterFilename());
        assertThat(otel).as("cached otelcol-contrib, not deleted by stop()").hasSize(1);
        assertThat(exporter).as("cached exporter, not deleted by stop()").hasSize(1);
        assertThat(otel.get(0)).hasBinaryContent(StandIns.bytesFor(StandIn.Role.OTELCOL));
        assertThat(leftoverTmpFiles(agent)).isEmpty();
        FileTime otelMtime = Files.getLastModifiedTime(otel.get(0));
        FileTime exporterMtime = Files.getLastModifiedTime(exporter.get(0));

        WorkflowRun second = j.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        j.assertLogContains("[on-monit] Using cached otelcol-contrib from " + otel.get(0), second);
        j.assertLogContains("[on-monit] Using cached " + exporterBinary() + " from " + exporter.get(0), second);
        j.assertLogNotContains("[on-monit] Caching ", second);
        assertThat(cached(agent, otelFilename())).containsExactly(otel.get(0));
        assertThat(Files.getLastModifiedTime(otel.get(0))).isEqualTo(otelMtime);
        assertThat(Files.getLastModifiedTime(exporter.get(0))).isEqualTo(exporterMtime);
    }

    @Test
    void uploadUsesNewKeyWhenBinaryReplaced(JenkinsRule j) throws Exception {
        StandIns.writeResourceRoot(TestAgents.jenkinsHomeEnv());
        DumbSlave agent = TestAgents.agent(j, "t", TestAgents.emptyDir(tmp), markerDir);
        WorkflowJob job = job(j);
        j.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        List<Path> before = cached(agent, otelFilename());
        assertThat(before).hasSize(1);

        // The admin replaces the binary under the same name; trailing bytes keep the stand-in runnable.
        Path source = TestAgents.jenkinsHomeEnv().resolve("opentelemetry-agent-metrics").resolve(otelFilename());
        Files.write(source, "\n# replaced\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        WorkflowRun second = j.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        j.assertLogContains("[on-monit] Caching otelcol-contrib in ", second);
        List<Path> after = cached(agent, otelFilename());
        assertThat(after).as("old key kept, new key added").hasSize(2).contains(before.get(0));
        Path added = after.stream().filter(p -> !p.equals(before.get(0))).findFirst().orElseThrow();
        assertThat(added).hasSameBinaryContentAs(source);
    }

    @Test
    void downloadCachesBinariesOnAgent(JenkinsRule j) throws Exception {
        String ne = "/" + exporterFilename();
        String oc = "/" + otelFilename();
        try (DownloadServer server = DownloadServer.start(Map.of(
                ne, StandIns.bytesFor(StandIns.exporterRole()),
                oc, StandIns.bytesFor(StandIn.Role.OTELCOL)))) {
            ONMonitConfig.get().setDownloadBaseUrl(server.baseUrl());
            DumbSlave agent = TestAgents.agent(j, "t", TestAgents.emptyDir(tmp), markerDir);
            WorkflowJob job = job(j);

            WorkflowRun first = j.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
            j.assertLogContains("(download otelcol-contrib from web to remote machine)", first);
            j.assertLogContains("[on-monit] Caching otelcol-contrib in ", first);
            List<Path> otel = cached(agent, otelFilename());
            assertThat(otel).hasSize(1);
            assertThat(otel.get(0).getParent().getFileName().toString())
                    .isEqualTo(BinaryCache.sha256Hex(server.baseUrl() + oc));
            assertThat(cached(agent, exporterFilename())).hasSize(1);
            assertThat(leftoverTmpFiles(agent)).isEmpty();
            FileTime mtime = Files.getLastModifiedTime(otel.get(0));

            WorkflowRun second = j.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
            j.assertLogContains("[on-monit] Using cached otelcol-contrib from " + otel.get(0), second);
            j.assertLogContains("[on-monit] Using cached " + exporterBinary() + " from ", second);
            assertThat(Files.getLastModifiedTime(otel.get(0))).isEqualTo(mtime);
            assertThat(server.requestedPaths()).as("second build downloads nothing")
                    .containsExactlyInAnyOrder(ne, oc);
        }
    }

    @Test
    void failedDownloadLeavesNoPartialCacheFile(JenkinsRule j) throws Exception {
        StandIns.writeResourceRoot(TestAgents.jenkinsHomeEnv());
        try (DownloadServer server = DownloadServer.start(Map.of())) {
            ONMonitConfig.get().setDownloadBaseUrl(server.baseUrl());
            DumbSlave agent = TestAgents.agent(j, "t", TestAgents.emptyDir(tmp), markerDir);
            WorkflowRun run = j.assertBuildStatus(Result.SUCCESS, job(j).scheduleBuild2(0));
            j.assertLogContains("(uploaded otelcol-contrib from master to remote machine)", run);
            assertThat(leftoverTmpFiles(agent)).isEmpty();
            assertThat(cached(agent, otelFilename())).as("only the uploaded copy").hasSize(1);
        }
    }

    @Test
    void cacheCanBeDisabled(JenkinsRule j) throws Exception {
        StandIns.writeResourceRoot(TestAgents.jenkinsHomeEnv());
        System.setProperty(BinaryCache.ENABLED_PROPERTY, "false");
        try {
            DumbSlave agent = TestAgents.agent(j, "t", TestAgents.emptyDir(tmp), markerDir);
            WorkflowRun run = j.assertBuildStatus(Result.SUCCESS, job(j).scheduleBuild2(0));
            j.assertLogContains("(uploaded otelcol-contrib from master to remote machine)", run);
            j.assertLogNotContains("[on-monit] Caching ", run);
            j.assertLogNotContains("[on-monit] Using cached ", run);
            assertThat(cacheRoot(agent)).doesNotExist();
            // the per-build copies in @tmp are deleted again by stop()
            Path wsTmp = Path.of(agent.getRootPath().getRemote()).resolve("workspace").resolve("p@tmp");
            if (Files.isDirectory(wsTmp)) {
                try (Stream<Path> files = Files.list(wsTmp)) {
                    assertThat(files.map(f -> f.getFileName().toString()).toList())
                            .noneMatch(n -> n.startsWith("otelcol-contrib") || n.startsWith(exporterBinary()));
                }
            }
        } finally {
            System.clearProperty(BinaryCache.ENABLED_PROPERTY);
        }
    }
}
