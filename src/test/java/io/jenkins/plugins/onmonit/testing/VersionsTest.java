package io.jenkins.plugins.onmonit.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jenkins.plugins.onmonit.ResourceUtil;
import org.junit.jupiter.api.Test;

class VersionsTest {

    @Test
    void readsVersionFromResourceFilename() {
        assertThat(Versions.of(ResourceUtil.getOtelFilename("linux", true))).isEqualTo("0.135.0");
    }

    @Test
    void failsWithoutVersion() {
        assertThatThrownBy(() -> Versions.of("none"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("none");
    }
}
