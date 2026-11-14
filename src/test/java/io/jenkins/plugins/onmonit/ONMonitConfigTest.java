package io.jenkins.plugins.onmonit;

import static org.assertj.core.api.Assertions.assertThat;

import hudson.util.FormValidation;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ONMonitConfigTest {

    @Test
    void grafanaDashboardRejectsUnknownVariables(JenkinsRule j) throws Exception {
        FormValidation v = ONMonitConfig.get().doCheckGrafanaDashboard("https://g/{foo}");
        assertThat(v.kind).isEqualTo(FormValidation.Kind.ERROR);
        assertThat(v.getMessage()).isEqualTo("Bad variables: {foo}");
    }

    @Test
    void grafanaDashboardAcceptsKnownVariables(JenkinsRule j) throws Exception {
        assertThat(ONMonitConfig.get().doCheckGrafanaDashboard("https://g/{jobName}/{endTime}").kind)
                .isEqualTo(FormValidation.Kind.OK);
    }

    @Test
    void grafanaDashboardRejectsInvalidUrl(JenkinsRule j) throws Exception {
        FormValidation v = ONMonitConfig.get().doCheckGrafanaDashboard("not a url");
        assertThat(v.kind).isEqualTo(FormValidation.Kind.ERROR);
        assertThat(v.getMessage()).isEqualTo("Invalid URL");
    }

    @Test
    void downloadBaseUrlValidation(JenkinsRule j) throws Exception {
        assertThat(ONMonitConfig.get().doCheckDownloadBaseUrl("").kind).isEqualTo(FormValidation.Kind.OK);
        assertThat(ONMonitConfig.get().doCheckDownloadBaseUrl("x").kind).isEqualTo(FormValidation.Kind.ERROR);
    }
}
