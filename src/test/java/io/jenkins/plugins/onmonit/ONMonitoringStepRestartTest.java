package io.jenkins.plugins.onmonit;

import static io.jenkins.plugins.onmonit.testing.JenkinsFixtures.globalEnv;
import static io.jenkins.plugins.onmonit.testing.Lifecycle.alive;
import static io.jenkins.plugins.onmonit.testing.Lifecycle.assertAlive;
import static io.jenkins.plugins.onmonit.testing.Lifecycle.awaitDead;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import hudson.ExtensionList;
import hudson.FilePath;
import hudson.Launcher;
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
import io.jenkins.plugins.onmonit.util.AvailablePortRetriever;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
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

    /**
     * A body to abort: {@code sleep} rather than {@link HeldBuilds#HOLD}, whose {@code waitUntil} sometimes loses the
     * abort (see {@code ONMonitoringStepTest.abortInsideBlockStopsProcesses}).
     */
    private static final String SLEEPING_BODY = "echo 'inside'\nsleep 600";

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

    /**
     * The build is aborted while the collector started after the controller restart is not ready yet: nothing is left
     * running once it would have become ready.
     */
    @Test
    void abortWhileCollectorRestartsLeavesNothing() throws Throwable {
        Path gate = tmp.resolve("gate");
        sessions.then(j -> {
            Files.writeString(gate, "open");
            WorkflowRun run = HeldBuilds.hold(j, job(j, onAgent(options(gate, null), SLEEPING_BODY)));
            j.waitForMessage("Sleeping for", run);
            beforeRestart = awaitMarkers(2);
            // the collector started after the restart waits for the gate
            Files.delete(gate);
        });
        sessions.then(j -> {
            WorkflowRun run = build(j);
            assertThat(awaitNewMarkers(2))
                    .extracting(Marker::role)
                    .containsExactlyInAnyOrder(StandIn.Role.OTELCOL, StandIns.exporterRole());
            abort(j, run);
            Files.writeString(gate, "open");
            awaitNothingAlive();
        });
    }

    /**
     * The body ends after the controller restart while restarting the processes has not launched any yet (it waits for
     * the agent's port lock, which the test holds), and the {@code node} block carries on: the processes launched after
     * the body ended are killed too, rather than left running on the agent.
     *
     * <p>An abort instead of the body ending would not show this: once the build ends, the step has no launcher left,
     * so restarting the processes fails before it launches any.
     */
    @Test
    void bodyEndingBeforeProcessesRestartLeavesNothing() throws Throwable {
        sessions.then(j -> {
            String script = "node('t') {\n"
                    + "onMonit" + options() + " {\n" + HeldBuilds.HOLD + "\n}\n"
                    + "echo 'after-onMonit'\n"
                    + "waitUntil(initialRecurrencePeriod: 200) { fileExists('release-node') }\n"
                    + "}";
            HeldBuilds.hold(j, job(j, script));
            beforeRestart = awaitMarkers(2);
            HeldReconnect.hold();
        });
        sessions.then(j -> {
            WorkflowRun run = build(j);
            HeldReconnect.awaitHeld();
            Semaphore portLock = AvailablePortRetriever.getSyncOjbectForLauncher(
                    new Launcher.RemoteLauncher(TaskListener.NULL, HeldReconnect.channel(), true));
            portLock.acquire();
            try {
                HeldReconnect.release();
                Awaitility.await().atMost(Duration.ofSeconds(60)).until(portLock::hasQueuedThreads);
                HeldBuilds.releaseOn(agent(j), run.getParent());
                j.waitForMessage("after-onMonit", run);
            } finally {
                portLock.release();
            }
            awaitNothingAlive();
            assertThat(StandIns.markers(markerDir)).hasSizeGreaterThan(2);
            agent(j).getWorkspaceFor(run.getParent()).child("release-node").write("x", "UTF-8");
            j.assertBuildStatusSuccess(j.waitForCompletion(run));
        });
    }

    /**
     * Like {@link #bodyEndingBeforeProcessesRestartLeavesNothing}, but the {@code node} block ends while the collector
     * launched after the body ended is not ready yet.
     */
    @Test
    void nodeBlockEndingWhileProcessesRestartLeavesNothing() throws Throwable {
        Path gate = tmp.resolve("gate");
        sessions.then(j -> {
            Files.writeString(gate, "open");
            String script = "node('t') {\n"
                    + "onMonit" + options(gate, null) + " {\n" + HeldBuilds.HOLD + "\n}\n"
                    + "echo 'after-onMonit'\n"
                    + "waitUntil(initialRecurrencePeriod: 200) { fileExists('release-node') }\n"
                    + "}";
            HeldBuilds.hold(j, job(j, script));
            beforeRestart = awaitMarkers(2);
            HeldReconnect.hold();
        });
        sessions.then(j -> {
            WorkflowRun run = build(j);
            HeldReconnect.awaitHeld();
            Semaphore portLock = AvailablePortRetriever.getSyncOjbectForLauncher(
                    new Launcher.RemoteLauncher(TaskListener.NULL, HeldReconnect.channel(), true));
            portLock.acquire();
            try {
                HeldReconnect.release();
                Awaitility.await().atMost(Duration.ofSeconds(60)).until(portLock::hasQueuedThreads);
                HeldBuilds.releaseOn(agent(j), run.getParent());
                j.waitForMessage("after-onMonit", run);
                // the collector launched after the body ended waits for the gate
                Files.delete(gate);
            } finally {
                portLock.release();
            }
            List<Marker> afterRestart = awaitNewMarkers(2);
            agent(j).getWorkspaceFor(run.getParent()).child("release-node").write("x", "UTF-8");
            j.assertBuildStatusSuccess(j.waitForCompletion(run));
            Files.writeString(gate, "open");
            awaitNothingAlive();
            // stopping without the step context still deletes the rendered config, which holds the OTLP headers
            assertThat(collectorConfig(afterRestart)).doesNotExist();
        });
    }

    /** The body ends while the collector restarted after the controller restart is not ready yet. */
    @Test
    void bodyEndingWhileCollectorRestartsLogsNoRestartFailure() throws Throwable {
        Path gate = tmp.resolve("gate");
        sessions.then(j -> {
            Files.writeString(gate, "open");
            String script = "node('t') {\n"
                    + "onMonit" + options(gate, null) + " {\n" + HeldBuilds.HOLD + "\n}\n"
                    + "echo 'after-onMonit'\n"
                    + "waitUntil(initialRecurrencePeriod: 200) { fileExists('release-node') }\n"
                    + "}";
            HeldBuilds.hold(j, job(j, script));
            beforeRestart = awaitMarkers(2);
            Files.delete(gate);
        });
        sessions.then(j -> {
            WorkflowRun run = build(j);
            awaitNewMarkers(2);
            HeldBuilds.releaseOn(agent(j), run.getParent());
            j.waitForMessage("after-onMonit", run);
            awaitNothingAlive();
            agent(j).getWorkspaceFor(run.getParent()).child("release-node").write("x", "UTF-8");
            j.assertBuildStatusSuccess(j.waitForCompletion(run));
            j.assertLogNotContains(RESTART_FAILED, run);
        });
    }

    /** The config file the collector among {@code markers} was started with. */
    private static Path collectorConfig(List<Marker> markers) {
        String prefix = "--config=file:";
        return markers.stream()
                .filter(m -> m.role() == StandIn.Role.OTELCOL)
                .flatMap(m -> m.args().stream())
                .filter(a -> a.startsWith(prefix))
                .map(a -> Path.of(a.substring(prefix.length())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no collector among " + markers));
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

    /** Aborts {@code run} and waits until it has ended as aborted. */
    private static void abort(JenkinsRule j, WorkflowRun run) throws Exception {
        run.doStop();
        try {
            Awaitility.await().atMost(Duration.ofSeconds(90)).until(() -> !run.isBuilding());
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("Build still running 90s after doStop; build log:\n" + JenkinsRule.getLog(run), e);
        }
        j.assertBuildStatus(Result.ABORTED, run);
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
        private static volatile Channel channel;

        static void reset() {
            CountDownLatch r = release;
            if (r != null) {
                r.countDown();
            }
            held = null;
            release = null;
            channel = null;
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

        /** The channel of the held connection, which becomes the agent's channel once released. */
        static Channel channel() {
            return channel;
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
            channel = ch;
            h.countDown();
            if (!r.await(2, TimeUnit.MINUTES)) {
                listener.getLogger().println("test did not release the held reconnection");
            }
        }
    }
}
