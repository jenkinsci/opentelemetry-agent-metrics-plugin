package io.jenkins.plugins.onmonit.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.jenkins.plugins.onmonit.ResourceUtil;
import io.jenkins.plugins.onmonit.util.RemoteComputerInfoRetriever;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
            Matcher m = Pattern.compile("(\\d+\\.\\d+\\.\\d+)").matcher(name);
            assertThat(m.find()).isTrue();
            Process p = new ProcessBuilder(file.toString(), "--version")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(p.exitValue()).as(name + " exit code: " + out).isZero();
            assertThat(out).as(name).contains("version " + m.group(1));
        }
    }
}
