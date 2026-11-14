package io.jenkins.plugins.onmonit.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;

/** Liveness checks for the processes the plugin starts. */
public final class Lifecycle {

    private Lifecycle() {}

    public static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** Asserts that each stand-in and its launcher (if any) is alive. */
    public static void assertAlive(List<Marker> markers) {
        for (Marker m : markers) {
            assertThat(alive(m.pid()))
                    .as("pid %d of %s alive", m.pid(), m.role())
                    .isTrue();
            if (m.parentPid() > 0) {
                assertThat(alive(m.parentPid()))
                        .as("parent pid %d of %s alive", m.parentPid(), m.role())
                        .isTrue();
            }
        }
    }

    /** Waits up to {@code timeout} until each stand-in and its launcher (if any) has exited. */
    public static void awaitDead(List<Marker> markers, Duration timeout) {
        Awaitility.await().atMost(timeout).untilAsserted(() -> {
            for (Marker m : markers) {
                assertThat(alive(m.pid()))
                        .as("pid %d of %s alive", m.pid(), m.role())
                        .isFalse();
                if (m.parentPid() > 0) {
                    assertThat(alive(m.parentPid()))
                            .as("parent pid %d of %s alive", m.parentPid(), m.role())
                            .isFalse();
                }
            }
        });
    }

    /** Commands of the live processes whose executable lies under {@code root}. */
    public static List<String> processesUnder(String root) {
        return ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .map(p -> p.info().command().orElse(""))
                .filter(c -> c.startsWith(root))
                .collect(Collectors.toList());
    }
}
