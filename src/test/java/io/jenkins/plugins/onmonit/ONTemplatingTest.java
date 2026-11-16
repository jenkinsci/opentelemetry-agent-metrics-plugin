package io.jenkins.plugins.onmonit;

import static org.assertj.core.api.Assertions.assertThat;

import hudson.EnvVars;
import hudson.model.ItemGroup;
import hudson.model.TaskListener;
import io.jenkins.plugins.onmonit.testing.FastScrapeTemplate;
import io.jenkins.plugins.onmonit.testing.RenderedConfig;
import java.util.Map;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ONTemplatingTest {

    private static final String ENDPOINT = "http://localhost:4317";

    private static WorkflowRun runJob(JenkinsRule j, ItemGroup<?> parent, String name) throws Exception {
        WorkflowJob job = parent instanceof MockFolder f
                ? f.createProject(WorkflowJob.class, name)
                : j.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition("echo 'hi'", true));
        return j.buildAndAssertSuccess(job);
    }

    private static EnvVars env(WorkflowRun run, String endpoint, String headers, String serviceName) throws Exception {
        EnvVars env = run.getEnvironment(TaskListener.NULL);
        if (endpoint != null) {
            env.put("OTEL_EXPORTER_OTLP_ENDPOINT", endpoint);
        }
        if (headers != null) {
            env.put("OTEL_EXPORTER_OTLP_HEADERS", headers);
        }
        if (serviceName != null) {
            env.put("OTEL_SERVICE_NAME", serviceName);
        }
        return env;
    }

    private static RenderedConfig render(WorkflowRun run, EnvVars env) {
        ONTemplating t = new ONTemplating();
        return RenderedConfig.parse(t.renderTemplate(t.getJobContext(run, env, 9100)));
    }

    /** Renders a top-level job's config with the given environment variables, as alternating key/value pairs. */
    private static RenderedConfig renderWithEnv(JenkinsRule j, String... kv) throws Exception {
        WorkflowRun run = runJob(j, j.jenkins, "p");
        EnvVars env = env(run, null, null, null);
        for (int i = 0; i < kv.length; i += 2) {
            env.put(kv[i], kv[i + 1]);
        }
        return render(run, env);
    }

    private static RenderedConfig renderTopLevel(JenkinsRule j, String endpoint, String headers, String serviceName)
            throws Exception {
        WorkflowRun run = runJob(j, j.jenkins, "p");
        return render(run, env(run, endpoint, headers, serviceName));
    }

    @Test
    void defaultTemplateRendersCiAttributes(JenkinsRule j) throws Exception {
        MockFolder a = j.createFolder("a");
        MockFolder b = a.createProject(MockFolder.class, "b");
        WorkflowRun run = runJob(j, b, "p");
        RenderedConfig cfg = render(run, env(run, ENDPOINT, null, null));
        assertThat(cfg.resourceAttribute("cicd.pipeline.name")).isEqualTo("a/b/p");
        assertThat(cfg.resourceAttribute("cicd.group.name")).isEqualTo("a/b");
        assertThat(cfg.resourceAttribute("cicd.pipeline.run.id")).isEqualTo("1");
        assertThat(cfg.resourceAttribute("cicd.controller.url"))
                .isEqualTo(j.getURL().toString());
        assertThat(cfg.scrapeTarget()).isEqualTo("localhost:9100");
        assertThat(cfg.exporterNames()).contains("otlp", "debug");
        assertThat(cfg.pipelineExporters()).containsExactly("otlp", "debug");
    }

    @Test
    void topLevelJobHasDashGroup(JenkinsRule j) throws Exception {
        assertThat(renderTopLevel(j, ENDPOINT, null, null).resourceAttribute("cicd.group.name"))
                .isEqualTo("-");
    }

    @Test
    void serviceNameFromEnvWinsOverGlobal(JenkinsRule j) throws Exception {
        ONMonitConfig.get().setOtelServiceName("global");
        assertThat(renderTopLevel(j, ENDPOINT, null, "env").resourceAttribute("service.name"))
                .isEqualTo("env");
    }

    @Test
    void serviceNameFallsBackToGlobal(JenkinsRule j) throws Exception {
        ONMonitConfig.get().setOtelServiceName("global");
        assertThat(renderTopLevel(j, ENDPOINT, null, null).resourceAttribute("service.name"))
                .isEqualTo("global");
    }

    @Test
    void endpointTrailingSlashIsStripped(JenkinsRule j) throws Exception {
        assertThat(renderTopLevel(j, "http://localhost:4317/", null, null).otlpEndpoint())
                .isEqualTo("http://localhost:4317");
    }

    @Test
    void endpointDefaultPortIsMadeExplicit(JenkinsRule j) throws Exception {
        assertThat(renderTopLevel(j, "http://collector", null, null).otlpEndpoint())
                .isEqualTo("http://collector:80");
    }

    @Test
    void noHeadersEnvMeansNoHeaders(JenkinsRule j) throws Exception {
        assertThat(renderTopLevel(j, ENDPOINT, null, null).otlpHeaders()).isEmpty();
    }

    @Test
    void simpleAuthorizationHeader(JenkinsRule j) throws Exception {
        assertThat(renderTopLevel(j, ENDPOINT, "Authorization=Bearer test-token", null)
                        .otlpHeaders())
                .isEqualTo(Map.of("Authorization", "Bearer test-token"));
    }

    @Test
    void customHeaderKey(JenkinsRule j) throws Exception {
        assertThat(renderTopLevel(j, ENDPOINT, "X-Api-Key=abc", null).otlpHeaders())
                .isEqualTo(Map.of("X-Api-Key", "abc"));
    }

    @Test
    void multipleHeaders(JenkinsRule j) throws Exception {
        assertThat(renderTopLevel(j, ENDPOINT, "a=b,Authorization=Bearer x", null)
                        .otlpHeaders())
                .isEqualTo(Map.of("a", "b", "Authorization", "Bearer x"));
    }

    @Test
    void urlEncodedHeaderValue(JenkinsRule j) throws Exception {
        assertThat(renderTopLevel(j, ENDPOINT, "Authorization=Basic%20abc", null)
                        .otlpHeaders())
                .isEqualTo(Map.of("Authorization", "Basic abc"));
    }

    @Test
    void headerValueWithQuote(JenkinsRule j) throws Exception {
        assertThat(renderTopLevel(j, ENDPOINT, "Authorization=it's \"x\" & y", null)
                        .otlpHeaders())
                .isEqualTo(Map.of("Authorization", "it's \"x\" & y"));
    }

    /** A custom template copied from the old default, using the deprecated otlpAuthHeader* variables. */
    @Test
    void legacyTemplateVariablesRenderAllHeaders(JenkinsRule j) throws Exception {
        String legacyLine = "[(${otlpAuthHeaderBefore})][[${otlpAuthHeader}]][(${otlpAuthHeaderAfter})]";
        String template = FastScrapeTemplate.builtIn().replace("[(${otlpHeadersYaml})]", legacyLine);
        assertThat(template).contains(legacyLine);
        FastScrapeTemplate.apply(template);
        assertThat(renderTopLevel(j, ENDPOINT, "a=b,X-Api-Key=abc", null).otlpHeaders())
                .isEqualTo(Map.of("a", "b", "X-Api-Key", "abc"));
    }

    /** A custom template that uses the deprecated otlpAuthHeader on its own still gets the header value. */
    @Test
    void legacyStandaloneAuthHeaderVariableKeepsValue(JenkinsRule j) throws Exception {
        String standalone = "headers:\n      Authorization: '[[${otlpAuthHeader}]]'";
        String template = FastScrapeTemplate.builtIn().replace("[(${otlpHeadersYaml})]", standalone);
        assertThat(template).contains(standalone);
        FastScrapeTemplate.apply(template);
        assertThat(renderTopLevel(j, ENDPOINT, "Authorization=Bearer test-token", null)
                        .otlpHeaders())
                .isEqualTo(Map.of("Authorization", "Bearer test-token"));
    }

    @Test
    void jobNameWithQuotesKeepsYamlValid(JenkinsRule j) throws Exception {
        // Windows forbids '"' in file names, so the job itself can't carry it; the template reads JOB_NAME from env.
        String name = "say \"hi\" it's";
        WorkflowRun run = runJob(j, j.jenkins, "p");
        EnvVars env = env(run, ENDPOINT, null, null);
        env.put("JOB_NAME", name);
        env.put("JOB_BASE_NAME", name);
        RenderedConfig cfg = render(run, env);
        assertThat(cfg.resourceAttribute("cicd.pipeline.name")).isEqualTo(name);
    }

    @Test
    void headerValueDollarIsEscapedForCollectorExpansion(JenkinsRule j) throws Exception {
        // otelcol expands ${env:...} in its config and reads $$ as a literal $, so every $ must be doubled.
        assertThat(renderTopLevel(j, ENDPOINT, "Authorization=a$b${env:HOME}", null)
                        .otlpHeaders())
                .isEqualTo(Map.of("Authorization", "a$$b$${env:HOME}"));
    }

    @Test
    void customTemplateCanQuoteValuesForYaml(JenkinsRule j) throws Exception {
        ONMonitConfig.get().setOtelConfigTemplate("""
                processors:
                  resource/ci:
                    attributes:
                    - action: insert
                      key: service.name
                      value: [(${yaml.quote(serviceName)})]
                """);
        String name = "say \"hi\" it's & more \\ #x";
        assertThat(renderTopLevel(j, ENDPOINT, null, name).resourceAttribute("service.name"))
                .isEqualTo(name);
    }

    @Test
    void grpcHttpEndpointIsInsecure(JenkinsRule j) throws Exception {
        RenderedConfig cfg = renderWithEnv(j, "OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317");
        assertThat(cfg.otlpExporterName()).isEqualTo("otlp");
        assertThat(cfg.otlpExporter()).containsEntry("tls", Map.of("insecure", true));
        assertThat(cfg.pipelineExporters()).containsExactly("otlp", "debug");
    }

    @Test
    void grpcHttpsEndpointHasNoTls(JenkinsRule j) throws Exception {
        RenderedConfig cfg = renderWithEnv(j, "OTEL_EXPORTER_OTLP_ENDPOINT", "https://c:4317");
        assertThat(cfg.otlpExporter()).doesNotContainKey("tls");
    }

    @Test
    void httpProtobufUsesOtlphttp(JenkinsRule j) throws Exception {
        RenderedConfig cfg = renderWithEnv(
                j, "OTEL_EXPORTER_OTLP_ENDPOINT", "http://c:4318", "OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf");
        assertThat(cfg.otlpExporterName()).isEqualTo("otlphttp");
        assertThat(cfg.exporterNames()).doesNotContain("otlp");
        assertThat(cfg.otlpEndpoint()).isEqualTo("http://c:4318");
        assertThat(cfg.otlpExporter()).doesNotContainKeys("tls", "encoding");
        assertThat(cfg.pipelineExporters()).containsExactly("otlphttp", "debug");
    }

    @Test
    void httpJsonSetsEncoding(JenkinsRule j) throws Exception {
        RenderedConfig cfg = renderWithEnv(
                j, "OTEL_EXPORTER_OTLP_ENDPOINT", "http://c:4318", "OTEL_EXPORTER_OTLP_PROTOCOL", "http/json");
        assertThat(cfg.otlpExporterName()).isEqualTo("otlphttp");
        assertThat(cfg.otlpExporter()).containsEntry("encoding", "json");
    }

    @Test
    void httpMetricsEndpointRendersAsIs(JenkinsRule j) throws Exception {
        RenderedConfig cfg = renderWithEnv(
                j,
                "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT",
                "http://m:4318/v1/metrics",
                "OTEL_EXPORTER_OTLP_PROTOCOL",
                "http/protobuf");
        assertThat(cfg.otlpExporter())
                .containsEntry("metrics_endpoint", "http://m:4318/v1/metrics")
                .doesNotContainKey("endpoint");
        assertThat(cfg.otlpEndpoint()).isEqualTo("http://m:4318/v1/metrics");
    }

    @Test
    void schemelessInsecureEndpointRendersTls(JenkinsRule j) throws Exception {
        RenderedConfig cfg = renderWithEnv(
                j, "OTEL_EXPORTER_OTLP_ENDPOINT", "collector:4317", "OTEL_EXPORTER_OTLP_INSECURE", "true");
        assertThat(cfg.otlpEndpoint()).isEqualTo("collector:4317");
        assertThat(cfg.otlpExporter()).containsEntry("tls", Map.of("insecure", true));
    }

    @Test
    void schemelessEndpointWithoutInsecureHasNoTls(JenkinsRule j) throws Exception {
        RenderedConfig cfg = renderWithEnv(j, "OTEL_EXPORTER_OTLP_ENDPOINT", "collector:4317");
        assertThat(cfg.otlpExporter()).doesNotContainKey("tls");
    }

    @Test
    void unsetEndpointRendersDefault(JenkinsRule j) throws Exception {
        assertThat(renderWithEnv(j).otlpEndpoint()).isEqualTo("http://localhost:4317");
    }

    @Test
    void metricsHeadersRendered(JenkinsRule j) throws Exception {
        RenderedConfig cfg = renderWithEnv(
                j,
                "OTEL_EXPORTER_OTLP_ENDPOINT",
                ENDPOINT,
                "OTEL_EXPORTER_OTLP_HEADERS",
                "a=1",
                "OTEL_EXPORTER_OTLP_METRICS_HEADERS",
                "c=3");
        assertThat(cfg.otlpHeaders()).isEqualTo(Map.of("c", "3"));
    }

    @Test
    void customTemplateSeesProtocolVariables(JenkinsRule j) throws Exception {
        ONMonitConfig.get().setOtelConfigTemplate("""
                p: [(${yaml.quote(otlpProtocol)})]
                e: [(${yaml.quote(otlpExporter)})]
                k: [(${yaml.quote(otlpEndpointKey)})]
                i: [(${otlpInsecure})]
                n: [(${yaml.quote(otlpEncoding)})]
                """);
        WorkflowRun run = runJob(j, j.jenkins, "p");
        EnvVars env = env(run, "http://c:4318", null, null);
        env.put("OTEL_EXPORTER_OTLP_PROTOCOL", "http/json");
        ONTemplating t = new ONTemplating();
        String rendered = t.renderTemplate(t.getJobContext(run, env, 9100));
        assertThat(rendered)
                .contains("p: \"http/json\"")
                .contains("e: \"otlphttp\"")
                .contains("k: \"endpoint\"")
                .contains("i: false")
                .contains("n: \"json\"");
    }
}
