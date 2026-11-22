package io.jenkins.plugins.onmonit;

import static io.jenkins.plugins.onmonit.testing.JenkinsFixtures.globalEnv;
import static io.jenkins.plugins.onmonit.testing.Lifecycle.alive;
import static io.jenkins.plugins.onmonit.testing.Lifecycle.assertAlive;
import static io.jenkins.plugins.onmonit.testing.Lifecycle.awaitDead;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import hudson.ExtensionList;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.slaves.ComputerListener;
import io.jenkins.plugins.onmonit.testing.DefaultPortLock;
import io.jenkins.plugins.onmonit.testing.HeldBuilds;
import io.jenkins.plugins.onmonit.testing.Marker;
import io.jenkins.plugins.onmonit.testing.StandIn;
import io.jenkins.plugins.onmonit.testing.StandIns;
import io.jenkins.plugins.onmonit.testing.TestAgents;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jenkins.slaves.restarter.SlaveRestarter;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.ChannelShutdownListener;
import org.jvnet.hudson.test.EndOfTestListener;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.InboundAgentExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

/**
 * {@code onMonit} across a controller restart (#143).
 *
 * <p>The agent is an inbound agent whose JVM, and with it the processes the plugin started, survives the restart, as
 * on inbound agents in production. The {@code TestAgents} agents would not do: their launcher kills the agent's whole
 * process tree when the controller shuts down, so checks for leftover processes would pass whatever the plugin does.
 *
 * <p>The inbound agent inherits the test JVM's environment, so the stand-ins are uploaded from the controller (see
 * {@link StandIns#writeResourceRoot}) and are told their marker directory through the step's options.
 */
@ExtendWith(DefaultPortLock.class)
class ONMonitoringStepRestartTest {

    private static final Duration DEAD = Duration.ofSeconds(15);

    private static final String AGENT = "remote";

    /** The warning logged when the processes cannot be restarted after a controller restart. */
    private static final String RESTART_FAILED = "[on-monit] WARNING: Could not restart node_exporter + otel-contrib "
            + "after the controller restart; continuing without metrics";

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    @RegisterExtension
    private final InboundAgentExtension inboundAgents = new InboundAgentExtension();

    @TempDir
    Path tmp;

    private Path markerDir;

    /** The stand-ins that were running when the controller went down. */
    private List<Marker> beforeRestart;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(StandIns.supported());
        markerDir = Files.createDirectories(tmp.resolve("markers"));
        TestAgents.deleteResourceRoot();
        HeldReconnect.reset();
    }

    @AfterEach
    void tearDown() {
        HeldReconnect.reset();
        if (markerDir != null) {
            StandIns.killAll(markerDir);
            TestAgents.deleteResourceRoot();
        }
    }

    /**
     * The body is running when the controller restarts: the processes from before the restart are killed, new ones run
     * for the rest of the body, and none are left after the build.
     */
    @Test
    void bodyRunningRestartsProcesses() throws Throwable {
        sessions.then(j -> {
            WorkflowJob job = job(j, onAgent(options(), HeldBuilds.HOLD));
            HeldBuilds.hold(j, job);
            beforeRestart = awaitMarkers(2);
            assertAlive(beforeRestart);
            HeldReconnect.hold();
        });
        sessions.then(j -> {
            WorkflowRun run = build(j);
            assertStillAliveAfterRestart();
            List<Marker> afterRestart = awaitNewMarkers(2);
            assertThat(afterRestart)
                    .extracting(Marker::role)
                    .containsExactlyInAnyOrder(StandIn.Role.OTELCOL, StandIns.exporterRole());
            awaitDead(beforeRestart, DEAD);
            awaitOccurrences(run, Messages.ONMonitoringStep_Started(), 2);
            // still inside the held body
            assertThat(run.isBuilding()).isTrue();
            assertAlive(afterRestart);
            HeldBuilds.releaseOn(agent(j), run.getParent());
            j.assertBuildStatusSuccess(j.waitForCompletion(run));
            awaitDead(StandIns.markers(markerDir), DEAD);
        });
    }

    /**
     * The controller restarts while the processes are still starting: the step fails and its processes are killed. The
     * restart happens in the same JVM, so the start-up thread from before the restart may still be alive; letting it
     * finish must not run the body or start a process.
     */
    @Test
    void restartWhileStartingFailsStepAndCleansUp() throws Throwable {
        Path gate = tmp.resolve("gate");
        sessions.then(j -> {
            WorkflowJob job = job(j, onAgent(options(gate, null), "echo 'body-ran'"));
            job.scheduleBuild2(0).waitForStart();
            beforeRestart = awaitMarkers(2);
            assertAlive(beforeRestart);
            HeldReconnect.hold();
        });
        sessions.then(j -> {
            WorkflowRun run = build(j);
            assertStillAliveAfterRestart();
            j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(run));
            awaitDead(beforeRestart, DEAD);
            Files.writeString(gate, "open");
            awaitNothingAlive();
            assertThat(StandIns.markers(markerDir)).hasSize(2);
            j.assertLogNotContains("body-ran", run);
        });
    }

    /**
     * Restarting the processes after the controller restart fails (node_exporter starts, otelcol does not): the build
     * log says so, nothing is left running, and the body carries on without metrics instead of being interrupted.
     */
    @Test
    void failedRestartDoesNotFailBuild() throws Throwable {
        Path fail = tmp.resolve("fail");
        sessions.then(j -> {
            WorkflowJob job = job(j, onAgent(options(null, fail), HeldBuilds.HOLD + "\necho 'after-restart'"));
            HeldBuilds.hold(j, job);
            beforeRestart = awaitMarkers(2);
            Files.writeString(fail, "otelcol fails from now on");
        });
        sessions.then(j -> {
            WorkflowRun run = build(j);
            awaitOccurrences(run, RESTART_FAILED, 1);
            List<Marker> afterRestart = awaitNewMarkers(1);
            assertThat(afterRestart).extracting(Marker::role).containsExactly(StandIns.exporterRole());
            awaitDead(StandIns.markers(markerDir), DEAD);
            HeldBuilds.releaseOn(agent(j), run.getParent());
            j.assertBuildStatusSuccess(j.waitForCompletion(run));
            j.assertLogContains("after-restart", run);
            j.assertLogContains("Failed to start otelcol-contrib", run);
        });
    }

    /** Creates the inbound agent with label {@code t}, and the stand-ins it gets uploaded. */
    private void agentAndStandIns(JenkinsRule j) throws Exception {
        // Otherwise the agent JVM restarts itself when it reconnects, as it does by default where it can.
        ExtensionList.lookup(SlaveRestarter.class).clear();
        // The test harness closes agent channels before Jenkins shuts down, unlike a real controller, which first
        // suspends the builds. A start-up still running would then fail the build before it could be resumed.
        ExtensionList<EndOfTestListener> endOfTest = ExtensionList.lookup(EndOfTestListener.class);
        endOfTest.remove(endOfTest.get(ChannelShutdownListener.class));
        assertThat(endOfTest.get(ChannelShutdownListener.class)).isNull();
        globalEnv(j, "OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317");
        StandIns.writeResourceRoot(TestAgents.jenkinsHomeEnv());
        inboundAgents.createAgent(
                j,
                InboundAgentExtension.Options.newBuilder()
                        .name(AGENT)
                        .label("t")
                        .build());
    }

    private WorkflowJob job(JenkinsRule j, String script) throws Exception {
        agentAndStandIns(j);
        WorkflowJob job = j.createProject(WorkflowJob.class, "p");
        job.setDefinition(new CpsFlowDefinition(script, true));
        return job;
    }

    private static WorkflowRun build(JenkinsRule j) {
        WorkflowRun run = j.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1);
        assertThat(run).isNotNull();
        return run;
    }

    private static Node agent(JenkinsRule j) {
        Node agent = j.jenkins.getNode(AGENT);
        assertThat(agent).isNotNull();
        return agent;
    }

    private static String onAgent(String options, String body) {
        return "node('t') { onMonit" + options + " {\n" + body + "\n} }";
    }

    /** Step options that only tell the stand-ins where to write their markers. */
    private String options() {
        return options(null, null);
    }

    /**
     * Step options for the stand-ins: their marker directory, and optionally a gate file (otelcol waits for it before
     * it is ready) and a fail file (otelcol exits at start if it exists).
     */
    private String options(Path gate, Path fail) {
        List<String> oc = new ArrayList<>(List.of(arg(StandIn.MARKER_DIR_ARG, markerDir)));
        if (gate != null) {
            oc.add(arg(StandIn.GATE_FILE_ARG, gate));
        }
        if (fail != null) {
            oc.add(arg(StandIn.FAIL_FILE_ARG, fail));
        }
        return "(neAdditionalOptions: '" + arg(StandIn.MARKER_DIR_ARG, markerDir) + "', ocAdditionalOptions: '"
                + String.join(" ", oc) + "')";
    }

    /** A double-quoted stand-in argument, so that a path with spaces stays one option. */
    private static String arg(String prefix, Path path) {
        return "\"" + prefix + path.toAbsolutePath().toString().replace('\\', '/') + "\"";
    }

    private List<Marker> awaitMarkers(int count) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .until(() -> StandIns.markers(markerDir).size() >= count);
        return StandIns.markers(markerDir);
    }

    /** Waits for {@code count} stand-ins started after the restart, and returns them. */
    private List<Marker> awaitNewMarkers(int count) {
        Set<Long> old = beforeRestart.stream().map(Marker::pid).collect(Collectors.toSet());
        List<Marker> fresh = new ArrayList<>();
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> {
            fresh.clear();
            StandIns.markers(markerDir).stream()
                    .filter(m -> !old.contains(m.pid()))
                    .forEach(fresh::add);
            return fresh.size() >= count;
        });
        return fresh;
    }

    /** Waits until the build log contains {@code text} at least {@code count} times. */
    private static void awaitOccurrences(WorkflowRun run, String text, int count) throws IOException {
        try {
            Awaitility.await()
                    .atMost(Duration.ofSeconds(60))
                    .until(() -> JenkinsRule.getLog(run).split(Pattern.quote(text), -1).length - 1
                            >= count);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError(
                    "'" + text + "' not logged " + count + " times; build log:\n" + JenkinsRule.getLog(run), e);
        }
    }

    /**
     * While the agent's reconnection is held, the stand-ins from before the restart must still be alive: otherwise the
     * test could not tell whether the plugin cleans them up. Then lets the agent reconnect and the build resume.
     */
    private void assertStillAliveAfterRestart() throws InterruptedException {
        HeldReconnect.awaitHeld();
        assertAlive(beforeRestart);
        HeldReconnect.release();
    }

    /** Waits until no recorded stand-in has been alive for 10 seconds, so that a late start would be caught. */
    private void awaitNothingAlive() {
        try {
            Awaitility.await()
                    .during(Duration.ofSeconds(10))
                    .atMost(Duration.ofSeconds(45))
                    .until(() -> StandIns.markers(markerDir).stream().noneMatch(m -> alive(m.pid())));
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("stand-ins still running: " + StandIns.markers(markerDir).stream()
                    .filter(m -> alive(m.pid()))
                    .toList(), e);
        }
    }

    /**
     * Holds the agent's reconnection after the restart while armed. The {@code node} step waits for its agent to
     * reconnect before {@code onMonit} resumes, so this keeps the plugin from touching the processes until the test has
     * looked at them. The static state survives the restart because the sessions run in the same JVM.
     */
    @TestExtension
    public static final class HeldReconnect extends ComputerListener {

        private static volatile CountDownLatch held;
        private static volatile CountDownLatch release;

        static void reset() {
            CountDownLatch r = release;
            if (r != null) {
                r.countDown();
            }
            held = null;
            release = null;
        }

        /** Arms the hold for the next time the agent connects. */
        static void hold() {
            held = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        /** Waits until the agent is connecting and held. */
        static void awaitHeld() throws InterruptedException {
            assertThat(held.await(2, TimeUnit.MINUTES))
                    .as("agent reconnected")
                    .isTrue();
        }

        static void release() {
            release.countDown();
        }

        @Override
        public void preOnline(Computer c, Channel ch, FilePath root, TaskListener listener)
                throws InterruptedException {
            CountDownLatch h = held;
            CountDownLatch r = release;
            if (h == null || r == null || !AGENT.equals(c.getName())) {
                return;
            }
            h.countDown();
            if (!r.await(2, TimeUnit.MINUTES)) {
                listener.getLogger().println("test did not release the held reconnection");
            }
        }
    }
}
