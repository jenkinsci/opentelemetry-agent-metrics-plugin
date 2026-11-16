package io.jenkins.plugins.onmonit.testing;

import hudson.FilePath;
import hudson.model.Node;
import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Builds that block inside a pipeline body until released.
 *
 * <p>The body must run inside a {@code node {}} block because {@code fileExists} needs a workspace. When it runs on an
 * agent, the release file goes into that agent's workspace for the job.
 */
public final class HeldBuilds {

    /**
     * Pipeline body that prints {@code inside} and then blocks until the {@code release} file exists. The 10-minute
     * timeout is a safety net for builds a failed test never releases; the longest intended hold is about 3 minutes.
     * The body stays valid when {@code \n} is replaced by {@code ; }.
     */
    public static final String HOLD = "echo 'inside'\n"
            + "timeout(time: 10, unit: 'MINUTES') { "
            + "waitUntil(initialRecurrencePeriod: 200) { fileExists('release') } }";

    /** How long {@link #hold(JenkinsRule, WorkflowJob)} waits for the build to start and to print {@code inside}. */
    public static final Duration START_TIMEOUT = Duration.ofMinutes(2);

    private HeldBuilds() {}

    /** Starts a build and waits up to {@link #START_TIMEOUT} until it printed {@code inside}. */
    public static WorkflowRun hold(JenkinsRule j, WorkflowJob job) throws Exception {
        return hold(j, job, START_TIMEOUT);
    }

    /**
     * Starts a build and waits until it printed {@code inside}. Fails with an {@link AssertionError} if the build does
     * not start, or does not print {@code inside}, within {@code timeout} each (for example when executors are
     * starved).
     */
    public static WorkflowRun hold(JenkinsRule j, WorkflowJob job, Duration timeout) throws Exception {
        WorkflowRun run;
        try {
            run = job.scheduleBuild2(0).getStartCondition().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("build of " + job.getFullName() + " did not start within " + timeout, e);
        }
        try {
            Awaitility.await()
                    .atMost(timeout)
                    .until(() -> JenkinsRule.getLog(run).contains("inside"));
        } catch (ConditionTimeoutException e) {
            throw new AssertionError(
                    run + " did not print 'inside' within " + timeout + "; build log:\n" + JenkinsRule.getLog(run), e);
        }
        return run;
    }

    /** Releases a build running on the controller. */
    public static void releaseOnController(JenkinsRule j, WorkflowJob job) throws Exception {
        File ws = new File(j.jenkins.getWorkspaceFor(job).getRemote());
        Files.createDirectories(ws.toPath());
        Files.writeString(new File(ws, "release").toPath(), "x");
    }

    /** Releases a build of {@code job} running on {@code agent}. */
    public static void releaseOn(Node agent, WorkflowJob job) throws Exception {
        FilePath ws = agent.getWorkspaceFor(job);
        if (ws == null) {
            throw new IllegalStateException("No workspace for " + job + " on " + agent);
        }
        ws.mkdirs();
        ws.child("release").write("x", "UTF-8");
    }

    /**
     * Test cleanup: releases every running build on {@code agent} and waits up to {@code timeout} for each to end. A
     * build still running after that is killed, and the method fails with its log.
     */
    public static void releaseAll(JenkinsRule j, Node agent, Duration timeout) throws Exception {
        for (WorkflowJob job : j.jenkins.getAllItems(WorkflowJob.class)) {
            for (WorkflowRun run : job.getBuilds()) {
                if (!run.isBuilding()) {
                    continue;
                }
                releaseOn(agent, job);
                try {
                    Awaitility.await().atMost(timeout).until(() -> !run.isBuilding());
                } catch (ConditionTimeoutException e) {
                    run.doKill();
                    throw new AssertionError(
                            run + " still running " + timeout + " after release; build log:\n"
                                    + JenkinsRule.getLog(run),
                            e);
                }
            }
        }
    }
}
