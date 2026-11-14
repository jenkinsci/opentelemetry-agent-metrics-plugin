package io.jenkins.plugins.onmonit.testing;

import hudson.FilePath;
import hudson.model.Node;
import java.io.File;
import java.nio.file.Files;
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

    /** Pipeline body that prints {@code inside} and then blocks until the {@code release} file exists. */
    public static final String HOLD =
            "echo 'inside'\nwaitUntil(initialRecurrencePeriod: 200) { fileExists('release') }";

    private HeldBuilds() {}

    /** Starts a build and waits until it printed {@code inside}. */
    public static WorkflowRun hold(JenkinsRule j, WorkflowJob job) throws Exception {
        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        j.waitForMessage("inside", run);
        return run;
    }

    /** Releases a build running on the controller. */
    public static void release(JenkinsRule j, WorkflowJob job) throws Exception {
        File ws = new File(j.jenkins.getWorkspaceFor(job).getRemote());
        Files.createDirectories(ws.toPath());
        Files.writeString(new File(ws, "release").toPath(), "x");
    }

    /** Releases a build running on {@code agent}. */
    public static void release(WorkflowJob job, Node agent) throws Exception {
        FilePath ws = agent.getWorkspaceFor(job);
        if (ws == null) {
            throw new IllegalStateException("No workspace for " + job + " on " + agent);
        }
        ws.mkdirs();
        ws.child("release").write("x", "UTF-8");
    }
}
