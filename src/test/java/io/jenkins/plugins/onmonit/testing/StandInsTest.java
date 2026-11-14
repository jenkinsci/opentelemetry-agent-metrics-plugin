package io.jenkins.plugins.onmonit.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.jenkins.plugins.onmonit.util.RemoteComputerInfoRetriever;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StandInsTest {

    private static final boolean WINDOWS = RemoteComputerInfoRetriever.isWindows();
    private static final String OTELCOL = WINDOWS ? "otelcol-contrib.exe" : "otelcol-contrib";
    private static final String EXPORTER = WINDOWS ? "windows_exporter.exe" : "node_exporter";

    private final List<Process> started = new ArrayList<>();
    private final List<Path> markerDirs = new ArrayList<>();

    @BeforeEach
    void supported() {
        assumeTrue(StandIns.supported(), "stand-in launchers cannot be built on this machine");
    }

    @AfterEach
    void cleanup() {
        // Wait for the processes to exit: on Windows a running launcher exe cannot be deleted with @TempDir.
        List<ProcessHandle> handles = new ArrayList<>();
        for (Process p : started) {
            p.toHandle().descendants().forEach(handles::add);
            handles.add(p.toHandle());
        }
        StandIns.destroyAndWait(handles);
        markerDirs.forEach(StandIns::killAll);
    }

    @Test
    void versionMatchesResourceUtil(@TempDir Path t) throws Exception {
        Path bin = StandIns.create(t.resolve("bin"));
        assertVersion(bin.resolve(OTELCOL), "version 0.135.0");
        assertVersion(bin.resolve(EXPORTER), WINDOWS ? "version 0.31.3" : "version 1.9.1");
    }

    @Test
    void otelcolCopiesConfigAndSignalsReady(@TempDir Path t) throws Exception {
        Path bin = StandIns.create(t.resolve("bin"));
        Path markers = Files.createDirectories(t.resolve("markers"));
        Path config = Files.writeString(t.resolve("c.yaml"), "x: 1");
        Running r = start(bin.resolve(OTELCOL), markers, "--config=file:" + config);

        r.awaitOutput("Everything is ready.");

        List<Marker> found = StandIns.markers(markers);
        assertThat(found).hasSize(1);
        Marker m = found.get(0);
        assertThat(m.role()).isEqualTo(StandIn.Role.OTELCOL);
        assertThat(m.args()).contains("--config=file:" + config);
        assertThat(m.configCopy()).isNotNull();
        assertThat(Files.readString(m.configCopy())).isEqualTo("x: 1");
        assertThat(m.port()).isNull();
    }

    @Test
    void exporterBindsItsPort(@TempDir Path t) throws Exception {
        Path bin = StandIns.create(t.resolve("bin"));
        Path markers = Files.createDirectories(t.resolve("markers"));
        int port = freePort();
        Running r = start(bin.resolve(EXPORTER), markers,
                "--web.disable-exporter-metrics", "--web.listen-address=:" + port);

        r.awaitOutput("Listening on");
        r.awaitOutput(String.valueOf(port));

        assertThatThrownBy(() -> new ServerSocket(port).close()).isInstanceOf(BindException.class);
        List<Marker> found = StandIns.markers(markers);
        assertThat(found).hasSize(1);
        Marker m = found.get(0);
        assertThat(m.role()).isEqualTo(WINDOWS ? StandIn.Role.WINDOWS_EXPORTER : StandIn.Role.NODE_EXPORTER);
        assertThat(m.port()).isEqualTo(port);
        assertThat(m.args()).containsExactly("--web.disable-exporter-metrics", "--web.listen-address=:" + port);
    }

    @Test
    void killingLauncherTreeLeavesNothingAlive(@TempDir Path t) throws Exception {
        Path bin = StandIns.create(t.resolve("bin"));
        Path markers = Files.createDirectories(t.resolve("markers"));
        Path config = Files.writeString(t.resolve("c.yaml"), "x: 1");
        Running r = start(bin.resolve(OTELCOL), markers, "--config=file:" + config);
        r.awaitOutput("Everything is ready.");
        Marker m = StandIns.markers(markers).get(0);
        assertThat(ProcessHandle.of(m.pid())).hasValueSatisfying(h -> assertThat(h.isAlive()).isTrue());

        r.process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        r.process.destroyForcibly();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(isAlive(m.pid())).as("stand-in pid %d", m.pid()).isFalse();
            if (m.parentPid() != -1) {
                assertThat(isAlive(m.parentPid())).as("launcher pid %d", m.parentPid()).isFalse();
            }
        });
    }

    @Test
    void killAllDestroysRecordedProcessesAndWaits(@TempDir Path t) throws Exception {
        Path bin = StandIns.create(t.resolve("bin"));
        Path markers = Files.createDirectories(t.resolve("markers"));
        int port = freePort();
        Running r = start(bin.resolve(EXPORTER), markers, "--web.listen-address=:" + port);
        r.awaitOutput("Listening on");
        Marker m = StandIns.markers(markers).get(0);

        StandIns.killAll(markers);

        assertThat(isAlive(m.pid())).as("stand-in pid %d right after killAll", m.pid()).isFalse();
        if (m.parentPid() != -1) {
            assertThat(isAlive(m.parentPid())).as("launcher pid %d right after killAll", m.parentPid()).isFalse();
        }
    }

    @Test
    void killAllSparesProcessThatReusedARecordedPid(@TempDir Path t) throws Exception {
        Path bin = StandIns.create(t.resolve("bin"));
        Path unrelatedMarkers = Files.createDirectories(t.resolve("unrelated"));
        Path config = Files.writeString(t.resolve("c.yaml"), "x: 1");
        // Stands in for an unrelated process that now holds a PID recorded in an old marker.
        Running unrelated = start(bin.resolve(OTELCOL), unrelatedMarkers, "--config=file:" + config);
        unrelated.awaitOutput("Everything is ready.");
        long pid = StandIns.markers(unrelatedMarkers).get(0).pid();
        Instant realStart = ProcessHandle.of(pid).flatMap(h -> h.info().startInstant()).orElseThrow();

        Path stale = Files.createDirectories(t.resolve("stale"));
        writeMarker(stale.resolve("OTELCOL-1.properties"), pid, realStart.minusSeconds(3600));
        writeMarker(stale.resolve("OTELCOL-2.properties"), pid, null);
        StandIns.killAll(stale);

        assertThat(isAlive(pid)).as("process with reused pid %d", pid).isTrue();
    }

    private static void writeMarker(Path file, long pid, Instant startInstant) throws IOException {
        Properties p = new Properties();
        p.setProperty("role", "OTELCOL");
        p.setProperty("pid", Long.toString(pid));
        p.setProperty("parentPid", Long.toString(pid));
        if (startInstant != null) {
            p.setProperty("startInstant", startInstant.toString());
            p.setProperty("parentStartInstant", startInstant.toString());
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, null);
        }
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void assertVersion(Path launcher, String expected) throws Exception {
        Process p = new ProcessBuilder(launcher.toString(), "--version").redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(1, TimeUnit.MINUTES)).isTrue();
        assertThat(p.exitValue()).as("exit code of %s --version, output: %s", launcher, out).isZero();
        assertThat(out).contains(expected);
    }

    private Running start(Path launcher, Path markerDir, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(launcher.toString());
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("ONMONIT_STANDIN_DIR", markerDir.toString());
        markerDirs.add(markerDir);
        Process p = pb.start();
        started.add(p);
        return new Running(p);
    }

    private static final class Running {
        final Process process;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Running(Process process) {
            this.process = process;
            Thread pump = new Thread(() -> {
                byte[] buf = new byte[4096];
                try (InputStream in = process.getInputStream()) {
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        synchronized (out) {
                            out.write(buf, 0, n);
                        }
                    }
                } catch (IOException ignored) {
                    // process went away
                }
            }, "stand-in-output");
            pump.setDaemon(true);
            pump.start();
        }

        String output() {
            synchronized (out) {
                return out.toString(StandardCharsets.UTF_8);
            }
        }

        void awaitOutput(String text) {
            await().atMost(Duration.ofMinutes(1))
                    .pollInterval(Duration.ofMillis(100))
                    .failFast("stand-in exited: " + text, () -> !process.isAlive())
                    .until(() -> output().contains(text));
        }
    }
}
