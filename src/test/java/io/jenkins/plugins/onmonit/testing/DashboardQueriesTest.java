package io.jenkins.plugins.onmonit.testing;

import static io.jenkins.plugins.onmonit.testing.DashboardQueries.REPO_DASHBOARD_CORE_EXPRS;
import static io.jenkins.plugins.onmonit.testing.DashboardQueries.REPO_DASHBOARD_EXPRS;
import static org.assertj.core.api.Assertions.assertThat;

import io.jenkins.plugins.onmonit.testing.DashboardQueries.LabelValuesQuery;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class DashboardQueriesTest {

    private static final Map<String, String> VARS =
            Map.of("job", "f/p", "jobid", "12", "workerid", "host:9100", "jobgroup", "f");

    private static DashboardQueries load() throws Exception {
        return DashboardQueries.load(DashboardQueries.repoDashboard());
    }

    @Test
    void loadsAllPanelExprs() throws Exception {
        assertThat(load().panelExprs())
                .hasSize(REPO_DASHBOARD_EXPRS)
                .allSatisfy(e -> assertThat(e).isNotBlank());
    }

    /** Fails when a dashboard edit drops queries the Prometheus test requires data for. */
    @Test
    void coreExprCountIsPinned() throws Exception {
        assertThat(load().panelExprs().stream().filter(DashboardQueries::isCore))
                .hasSize(REPO_DASHBOARD_CORE_EXPRS);
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
        List<String> substituted =
                q.panelExprs().stream().map(e -> q.substitute(e, VARS)).toList();
        assertThat(substituted)
                .hasSize(REPO_DASHBOARD_EXPRS)
                .allSatisfy(e -> assertThat(e).doesNotContain("$"));
        assertThat(q.labelValuesVariables())
                .allSatisfy(
                        v -> assertThat(q.substitute(v.metricSelector(), VARS)).doesNotContain("$"));
        // $jobid / $jobgroup must not be mangled by the shorter $job.
        assertThat(q.substitute("a{x=\"$jobid\",y=\"${jobgroup}\",z=\"$job\"}[$__rate_interval]", VARS))
                .isEqualTo("a{x=\"12\",y=\"f\",z=\"f/p\"}[10s]");
        assertThat(q.substitute("$__interval $__range $diskdevices", Map.of()))
                .isEqualTo("2s 1m [a-z]+|nvme[0-9]+n[0-9]+|mmcblk[0-9]+");
    }

    @Test
    void substitutionMatchesSubstitute() throws Exception {
        DashboardQueries q = load();
        UnaryOperator<String> substitution = q.substitution(VARS);
        assertThat(q.panelExprs())
                .allSatisfy(e -> assertThat(substitution.apply(e)).isEqualTo(q.substitute(e, VARS)));
    }

    /** {@code String.valueOf(double)} would give {@code 1.763300000123E9}. */
    @Test
    void promTimeIsPlainDecimal() {
        assertThat(DashboardQueries.promTime(Instant.ofEpochMilli(1763300000123L)))
                .isEqualTo("1763300000.123");
    }
}
