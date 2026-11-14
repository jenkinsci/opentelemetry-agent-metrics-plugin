package io.jenkins.plugins.onmonit.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.jenkins.plugins.onmonit.testing.DashboardQueries.LabelValuesQuery;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DashboardQueriesTest {

    private static DashboardQueries load() throws Exception {
        return DashboardQueries.load(DashboardQueries.repoDashboard());
    }

    @Test
    void loadsAllPanelExprs() throws Exception {
        assertThat(load().panelExprs()).hasSize(275).allSatisfy(e -> assertThat(e).isNotBlank());
    }

    @Test
    void parsesLabelValuesVariables() throws Exception {
        assertThat(load().labelValuesVariables())
                .containsExactly(
                        new LabelValuesQuery("jobgroup", "node_uname_info", "cicd_group_name"),
                        new LabelValuesQuery(
                                "job", "node_uname_info{cicd_group_name=\"$jobgroup\"}", "cicd_pipeline_name"),
                        new LabelValuesQuery(
                                "jobid", "node_uname_info{cicd_pipeline_name=\"$job\"}", "cicd_pipeline_run_id"),
                        new LabelValuesQuery(
                                "workerid",
                                "node_uname_info{cicd_pipeline_name=\"$job\",cicd_pipeline_run_id=~\"$jobid\"}",
                                "cicd_worker_id"));
    }

    @Test
    void substitutesAllVariables() throws Exception {
        DashboardQueries q = load();
        Map<String, String> vars = Map.of("job", "f/p", "jobid", "12", "workerid", "host:9100", "jobgroup", "f");
        List<String> substituted =
                q.panelExprs().stream().map(e -> q.substitute(e, vars)).toList();
        assertThat(substituted).hasSize(275).allSatisfy(e -> assertThat(e).doesNotContain("$"));
        assertThat(q.labelValuesVariables())
                .allSatisfy(v -> assertThat(q.substitute(v.metricSelector(), vars)).doesNotContain("$"));
        // $jobid / $jobgroup must not be mangled by the shorter $job.
        assertThat(q.substitute("a{x=\"$jobid\",y=\"${jobgroup}\",z=\"$job\"}[$__rate_interval]", vars))
                .isEqualTo("a{x=\"12\",y=\"f\",z=\"f/p\"}[10s]");
        assertThat(q.substitute("$__interval $__range $diskdevices", Map.of()))
                .isEqualTo("2s 1m [a-z]+|nvme[0-9]+n[0-9]+|mmcblk[0-9]+");
    }
}
