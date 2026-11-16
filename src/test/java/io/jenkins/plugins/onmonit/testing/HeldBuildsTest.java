package io.jenkins.plugins.onmonit.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import hudson.slaves.DumbSlave;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.awaitility.Awaitility;
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

    /** One stuck build must not leave the others running: all are killed and all are reported. */
    @Test
    void releaseAllKillsAndReportsEveryStuckBuild(JenkinsRule j) throws Exception {
        DumbSlave agent = j.createOnlineSlave();
        List<WorkflowRun> runs = new ArrayList<>();
        for (String name : List.of("a", "b")) {
            WorkflowJob job = j.createProject(WorkflowJob.class, name);
            // Waits for an executor that never comes, so releasing it has no effect.
            job.setDefinition(new CpsFlowDefinition("node('no-such-label') { echo 'x' }", true));
            runs.add(job.scheduleBuild2(0).waitForStart());
        }

        assertThatThrownBy(() -> HeldBuilds.releaseAll(j, agent, Duration.ofSeconds(2)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("a #1")
                .hasMessageContaining("b #1");
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(runs).noneMatch(WorkflowRun::isBuilding));
    }
}
