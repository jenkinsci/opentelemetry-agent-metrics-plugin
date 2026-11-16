package io.jenkins.plugins.onmonit;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudbees.hudson.plugins.folder.Folder;
import io.jenkins.plugins.onmonit.testing.HeldBuilds;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.List;

@WithJenkins
class MonitoringActionTest {

    private static final String BODY = "echo 'hi'";

    private static WorkflowJob job(JenkinsRule j, String name, String body) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(wrap(body, "launchCollector: false"), true));
        return job;
    }

    private static String wrap(String body, String args) {
        return "onMonit(" + args + ") {\n" + body + "\n}";
    }

    private static MonitoringAction action(WorkflowRun run) {
        MonitoringAction a = run.getAction(MonitoringAction.class);
        assertThat(a).isNotNull();
        return a;
    }

    @Test
    void noOnMonitNoAction(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "plain");
        job.setDefinition(new CpsFlowDefinition("echo 'hi'", true));
        WorkflowRun run = j.buildAndAssertSuccess(job);
        assertThat(run.getAction(MonitoringAction.class)).isNull();
    }

    @Test
    void noDashboardConfiguredLinksToConfigure(JenkinsRule j) throws Exception {
        WorkflowJob job = job(j, "p", BODY);
        WorkflowRun run = j.buildAndAssertSuccess(job);
        List<MonitoringAction.MonitoringDashboardLink> links = action(run).getLinks();
        assertThat(links).hasSize(1);
        assertThat(links.get(0).getLabel()).isEqualTo("Please define an ONMonit dashboard URL in Jenkins configuration");
        assertThat(links.get(0).getUrl()).endsWith("/configure");
    }

    @Test
    void globalDashboardIsExpanded(JenkinsRule j) throws Exception {
        ONMonitConfig.get().setGrafanaDashboard("https://g/d?g={jobGroup}&n={jobName}&id={jobId}&from={startTime}&to={endTime}");
        Folder folder = j.createProject(Folder.class, "a");
        WorkflowJob job = folder.createProject(WorkflowJob.class, "p");
        job.setDefinition(new CpsFlowDefinition(wrap(BODY, "launchCollector: false"), true));
        WorkflowRun run = j.buildAndAssertSuccess(job);
        long start = run.getStartTimeInMillis();
        MonitoringAction.MonitoringDashboardLink link = action(run).getLinks().get(0);
        assertThat(link.getLabel()).isEqualTo("View build with Grafana");
        assertThat(link.getUrl()).isEqualTo("https://g/d?g=a&n=a%2Fp&id=1&from=" + start + "&to=" + (start + run.getDuration()));
    }

    @Test
    void endTimeIsNowWhileRunning(JenkinsRule j) throws Exception {
        ONMonitConfig.get().setGrafanaDashboard("https://g/d?to={endTime}");
        WorkflowJob job = j.createProject(WorkflowJob.class, "p");
        job.setDefinition(new CpsFlowDefinition("node {\n" + wrap(HeldBuilds.HOLD, "launchCollector: false") + "\n}", true));
        WorkflowRun run = HeldBuilds.hold(j, job);
        try {
            assertThat(action(run).getLinks().get(0).getUrl()).contains("to=now");
        } finally {
            HeldBuilds.releaseOnController(j, job);
            j.assertBuildStatusSuccess(j.waitForCompletion(run));
        }
    }

    @Test
    void stepDashboardUrlOverridesGlobal(JenkinsRule j) throws Exception {
        ONMonitConfig.get().setGrafanaDashboard("https://global/{jobId}");
        WorkflowJob job = j.createProject(WorkflowJob.class, "p");
        job.setDefinition(new CpsFlowDefinition(
                wrap(BODY, "launchCollector: false, dashboardUrl: 'https://s/{jobId}'"), true));
        WorkflowRun run = j.buildAndAssertSuccess(job);
        assertThat(action(run).getLinks().get(0).getUrl()).isEqualTo("https://s/1");
    }

    @Test
    void jobPageShowsViewLastBuild(JenkinsRule j) throws Exception {
        ONMonitConfig.get().setGrafanaDashboard("https://g/{jobId}");
        WorkflowJob job = job(j, "p", BODY);
        j.buildAndAssertSuccess(job);
        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            HtmlPage page = wc.getPage(job);
            assertThat(page.asNormalizedText()).contains("View last build with Grafana");
        }
    }

    @Test
    void buildPageShowsLink(JenkinsRule j) throws Exception {
        ONMonitConfig.get().setGrafanaDashboard("https://g/{jobName}/{jobId}");
        WorkflowJob job = job(j, "p", BODY);
        WorkflowRun run = j.buildAndAssertSuccess(job);
        String expected = action(run).getLinks().get(0).getUrl();
        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            HtmlPage page = wc.getPage(run);
            assertThat(page.getAnchors()).extracting(HtmlAnchor::getHrefAttribute).contains(expected);
        }
    }
}
