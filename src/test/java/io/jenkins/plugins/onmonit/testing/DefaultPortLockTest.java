package io.jenkins.plugins.onmonit.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultPortLockTest {

    @TempDir
    Path tmp;

    @Test
    void anotherJvmCannotTakeTheLockUntilItIsReleased() throws Exception {
        Path file = tmp.resolve("port.lock");
        try (DefaultPortLock.Held held = DefaultPortLock.acquire(file, Duration.ofSeconds(5))) {
            assertThat(tryInOtherJvm(file)).isEqualTo(DefaultPortLock.BUSY);
        }
        assertThat(tryInOtherJvm(file)).isZero();
    }

    @Test
    void waitingForALockHeldElsewhereIsBounded() throws Exception {
        Path file = tmp.resolve("port.lock");
        Process holder = otherJvm(file, "hold").start();
        try {
            assertThat(holder.getInputStream().read())
                    .as("holder signals it has the lock")
                    .isEqualTo('L');
            assertThatThrownBy(() -> DefaultPortLock.acquire(file, Duration.ofMillis(300)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(file.toString());
        } finally {
            holder.destroyForcibly().waitFor();
        }
        DefaultPortLock.acquire(file, Duration.ofSeconds(5)).close();
    }

    private static int tryInOtherJvm(Path file) throws Exception {
        return Processes.run(Duration.ofMinutes(1), otherJvm(file, "try").command())
                .exitCode();
    }

    /** Runs {@link DefaultPortLock.Probe} with only the test-classes directory on the class path. */
    private static ProcessBuilder otherJvm(Path file, String mode) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path classes = Path.of(DefaultPortLock.Probe.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        return new ProcessBuilder(
                List.of(java, "-cp", classes.toString(), DefaultPortLock.Probe.class.getName(), mode, file.toString()));
    }
}
