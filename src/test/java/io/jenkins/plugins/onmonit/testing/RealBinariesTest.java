package io.jenkins.plugins.onmonit.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.jenkins.plugins.onmonit.ResourceUtil;
import io.jenkins.plugins.onmonit.util.RemoteComputerInfoRetriever;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("real-binaries")
class RealBinariesTest {

    @Test
    void installsRunnableBinaries(@TempDir Path home) throws Exception {
        RealBinaries.installResourceRoot(home);

        String os = RemoteComputerInfoRetriever.getOs();
        boolean amd64 = RemoteComputerInfoRetriever.isAmd64();
        List<String> names = List.of(
                ResourceUtil.getOtelFilename(os, amd64), ResourceUtil.getNodeExporterFilename(os, amd64));
        for (String name : names) {
            Path file = home.resolve("opentelemetry-agent-metrics").resolve(name);
            Processes.Completed done = Processes.run(Duration.ofSeconds(60), List.of(file.toString(), "--version"));
            assertThat(done.exitCode()).as(name + " exit code: " + done.output()).isZero();
            assertThat(done.output()).as(name).contains("version " + Versions.of(name));
        }
    }
}
