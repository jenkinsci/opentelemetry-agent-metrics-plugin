package io.jenkins.plugins.onmonit.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import hudson.AbortException;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import hudson.util.StreamTaskListener;
import io.jenkins.plugins.onmonit.testing.StandIn;
import io.jenkins.plugins.onmonit.testing.StandIns;
import io.jenkins.plugins.onmonit.util.ComputerInfo;
import io.jenkins.plugins.onmonit.util.RemoteComputerInfoRetriever;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link ExecRemoteOtelContribProcess} against the otelcol stand-in, launched locally. */
class ExecRemoteOtelContribProcessTest {

    private static final boolean WINDOWS = RemoteComputerInfoRetriever.isWindows();

    @TempDir
    Path tmp;

    private Path bin;

    private Path markerDir;

    private final ByteArrayOutputStream log = new ByteArrayOutputStream();

    private TaskListener listener;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(StandIns.supported(), "stand-in launchers cannot be built on this machine");
        bin = StandIns.create(tmp.resolve("bin"));
        markerDir = Files.createDirectories(tmp.resolve("markers"));
        listener = new StreamTaskListener(log, StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() {
        if (markerDir != null) {
            StandIns.killAll(markerDir);
            StandIns.deleteWithRetry(bin);
        }
    }

    /** A collector that exits on its own while starting is reported in the build log, with its output. */
    @Test
    void collectorExitingWhileStartingIsReported() throws Exception {
        Path fail = Files.writeString(tmp.resolve("fail"), "fail");
        ExecRemoteOtelContribProcess oc = process(StandIn.FAIL_FILE_ARG + fail);
        assertThatThrownBy(() -> oc.start(listener, "receivers: {}\n"))
                .isInstanceOf(AbortException.class)
                .hasMessage("Failed to start otelcol-contrib");
        assertThat(log()).contains("Failed to start otelcol-contrib: ");
        oc.stop(listener);
    }

    /**
     * A collector stopped while it is starting, as {@code onMonit} does when its body ends during a restart, fails the
     * start without reporting a failed start in the build log: nothing went wrong with the collector.
     */
    @Test
    void collectorStoppedWhileStartingIsNotReported() throws Exception {
        Path gate = tmp.resolve("gate");
        ExecRemoteOtelContribProcess oc = process(StandIn.GATE_FILE_ARG + gate);
        CompletableFuture<Void> start = CompletableFuture.runAsync(() -> {
            try {
                oc.start(listener, "receivers: {}\n");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        // the collector waits for the gate, which never opens
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> !StandIns.markers(markerDir).isEmpty());
        oc.stop(listener);
        Throwable failure = catchThrowable(() -> start.get(30, TimeUnit.SECONDS));
        assertThat(log()).doesNotContain("Failed to start");
        assertThat(failure)
                .isInstanceOf(ExecutionException.class)
                .rootCause()
                .isInstanceOf(AbortException.class)
                .hasMessage("otelcol-contrib was stopped while starting");
    }

    private ExecRemoteOtelContribProcess process(String option) throws Exception {
        Path executable = bin.resolve(WINDOWS ? "otelcol-contrib.exe" : "otelcol-contrib");
        Launcher launcher = new Launcher.LocalLauncher(listener);
        return new ExecRemoteOtelContribProcess(
                () -> launcher,
                listener,
                localInfo(),
                new FilePath(Files.createDirectories(tmp.resolve("ws@tmp")).toFile()),
                "test-cookie-" + System.nanoTime(),
                StandIn.MARKER_DIR_ARG + markerDir + " " + option,
                false) {
            @Override
            protected ArgumentListBuilder getCmd() {
                return new ArgumentListBuilder(executable.toString());
            }
        };
    }

    private static ComputerInfo localInfo() throws Exception {
        try {
            return new RemoteComputerInfoRetriever.InfoCallable().call();
        } catch (Exception | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    private String log() {
        return log.toString(StandardCharsets.UTF_8);
    }
}
