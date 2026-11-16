package io.jenkins.plugins.onmonit.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.jenkins.plugins.onmonit.ResourceUtil;
import io.jenkins.plugins.onmonit.util.RemoteComputerInfoRetriever;
import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcessesTest {

    private static String java() {
        return Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        RemoteComputerInfoRetriever.isWindows() ? "java.exe" : "java")
                .toString();
    }

    private static String codeSource(Class<?> c) throws Exception {
        return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
    }

    @Test
    void returnsExitCodeAndOutput() throws Exception {
        Processes.Completed done = Processes.run(Duration.ofMinutes(1), List.of(java(), "-version"));
        assertThat(done.exitCode()).isZero();
        assertThat(done.output()).contains("version");
    }

    /** A command that reads stdin (PowerShell can) must see end-of-input, not wait for the caller to close a pipe. */
    @Test
    void stdinIsEmpty() throws Exception {
        assumeFalse(RemoteComputerInfoRetriever.isWindows(), "uses sh");
        Processes.Completed done = Processes.run(Duration.ofSeconds(10), List.of("sh", "-c", "cat; echo done"));
        assertThat(done.output()).isEqualTo("done\n");
    }

    /** Console output in another code page (Windows OEM) must not hide the result behind a decoding error. */
    @Test
    void nonUtf8OutputIsDecodedLeniently() throws Exception {
        assumeFalse(RemoteComputerInfoRetriever.isWindows(), "uses sh");
        Processes.Completed done = Processes.run(Duration.ofSeconds(10), List.of("sh", "-c", "printf 'a\\377b'"));
        assertThat(done.output()).isEqualTo("a�b");
    }

    /** A process that never exits must not block the caller in a read of its output. */
    @Test
    void timesOutInsteadOfBlockingOnOutput() throws Exception {
        List<String> cmd = List.of(
                java(),
                "-cp",
                codeSource(StandIn.class) + File.pathSeparator + codeSource(ResourceUtil.class),
                StandIn.class.getName(),
                "OTELCOL");
        assertThatThrownBy(() -> Processes.run(Duration.ofSeconds(5), cmd))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("did not exit within")
                .hasMessageContaining("Everything is ready.");
    }
}
