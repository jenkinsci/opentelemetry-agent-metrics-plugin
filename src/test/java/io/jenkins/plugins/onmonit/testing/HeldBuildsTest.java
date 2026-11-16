package io.jenkins.plugins.onmonit.testing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class HeldBuildsTest {

    /** A build that never gets an executor (no agent has the label) must fail the test, not hang it. */
    @Test
    void holdFailsWithinTimeoutWhenBuildNeverReachesBody(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "p");
        job.setDefinition(new CpsFlowDefinition("node('no-such-label') {\n" + HeldBuilds.HOLD + "\n}", true));
        try {
            assertThatThrownBy(() -> HeldBuilds.hold(j, job, Duration.ofSeconds(5)))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("inside");
        } finally {
            WorkflowRun run = job.getLastBuild();
            if (run != null && run.isBuilding()) {
                run.doKill();
            }
        }
    }
}
