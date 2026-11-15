package io.jenkins.plugins.onmonit.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Kept apart from {@link StandInsTest}, whose set-up skips every test where launchers cannot be built. */
class StandInsRequiredTest {

    @Test
    void requiredOnlyWhenRequireEnvIsTrue() {
        assertThat(StandIns.required(Map.of("JENKINS_URL", "https://ci"))).isFalse();
        assertThat(StandIns.required(Map.of())).isFalse();
        assertThat(StandIns.required(Map.of("ONMONIT_REQUIRE_STAND_INS", "false"))).isFalse();
        assertThat(StandIns.required(Map.of("ONMONIT_REQUIRE_STAND_INS", "true"))).isTrue();
    }
}
