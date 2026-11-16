package io.jenkins.plugins.onmonit.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Offline checks of the {@link RealBinaries} cache helpers; the downloads themselves are in RealBinariesTest. */
class RealBinariesCacheTest {

    @TempDir
    Path tmp;

    private Path sums() throws IOException {
        return Files.writeString(
                tmp.resolve("sha256sums.txt"),
                "abc123  node_exporter-1.9.1.linux-amd64.tar.gz\n"
                        + "def456  node_exporter-1.9.1.linux-arm64.tar.gz\n");
    }

    @Test
    void expectedSha256FindsAsset() throws Exception {
        assertThat(RealBinaries.expectedSha256(sums(), "node_exporter-1.9.1.linux-arm64.tar.gz"))
                .isEqualTo("def456");
    }

    @Test
    void expectedSha256FailsForMissingAsset() throws Exception {
        Path sums = sums();
        assertThatThrownBy(() -> RealBinaries.expectedSha256(sums, "node_exporter-1.9.1.darwin-amd64.tar.gz"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("node_exporter-1.9.1.darwin-amd64.tar.gz");
    }

    @Test
    void sha256OfFile() throws Exception {
        Path f = Files.writeString(tmp.resolve("f"), "abc");
        assertThat(RealBinaries.sha256(f))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    /** An identical binary is left alone, so a copy still running on Windows does not make the install fail. */
    @Test
    void installIfChangedLeavesIdenticalTargetUntouched() throws Exception {
        Path target = Files.writeString(tmp.resolve("bin"), "same");
        FileTime old = FileTime.from(Instant.parse("2000-01-01T00:00:00Z"));
        Files.setLastModifiedTime(target, old);
        Path staged = Files.writeString(tmp.resolve("bin.new"), "same");

        RealBinaries.installIfChanged(staged, target);

        assertThat(Files.getLastModifiedTime(target)).isEqualTo(old);
        assertThat(staged).doesNotExist();
    }

    @Test
    void installIfChangedReplacesDifferentTarget() throws Exception {
        Path target = Files.writeString(tmp.resolve("bin"), "old");
        Path staged = Files.writeString(tmp.resolve("bin.new"), "new");

        RealBinaries.installIfChanged(staged, target);

        assertThat(target).hasContent("new");
        assertThat(staged).doesNotExist();
    }
}
