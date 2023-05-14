package io.jenkins.plugins.onmonit;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import hudson.EnvVars;
import hudson.model.Run;
import jenkins.model.Jenkins;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

public class ONTemplating {
    //private String configTemplate = null;
    private static TemplateEngine templateEngine = null;

    /*String getConfigFileTemplate() throws IOException, URISyntaxException {
        if (configTemplate != null) {
            return configTemplate;
        }
        String resourceName = "otel.yaml.tmpl";
        ClassLoader cl = ONTemplating.class.getClassLoader();
        configTemplate = Files.readString(Paths.get(cl.getResource(resourceName).toURI()));
        return configTemplate;
    }*/

    TemplateEngine getTemplateEngine() {
        if (templateEngine != null) {
            return templateEngine;
        }
        final ClassLoaderTemplateResolver templateResolver = new ClassLoaderTemplateResolver(getClass().getClassLoader());
        templateResolver.setTemplateMode(TemplateMode.TEXT);
        templateResolver.setPrefix("io/jenkins/plugins/onmonit/");
        templateResolver.setSuffix(".tmpl");
        templateResolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        templateResolver.setCheckExistence(true);
        final TemplateEngine tEngine = new TemplateEngine();
        tEngine.setTemplateResolver(templateResolver);
        templateEngine = tEngine;
        return templateEngine;
    }

    String renderTemplate(Context context) {
        return getTemplateEngine().process("otel.yaml", context);
    }


    private String toOtelCompatibleUrl(String urlStr) {
        try {
            URL url = new URL(urlStr);
            if (url.getPort() == -1) {
                url = new URL(url.getProtocol(), url.getHost(), url.getDefaultPort(), url.getFile());
            }
            if ("/".equals(url.getFile())) {
                url = new URL(url.getProtocol(), url.getHost(), url.getPort(), "");
            }
            return url.toString();
        } catch(MalformedURLException e) {
            return urlStr;
        }
    }

    private String trimSuffix(String original, String suffix) {
        if (original.endsWith(suffix)) {
            return original.substring(0, original.length() - suffix.length());
        }
        return original;
    }

    private String trimWithDefault(String original, String suffix, String _default) {
        String trimmed = trimSuffix(original, suffix);
        return trimmed.length()==0 ? _default : trimSuffix(trimmed, "/");
    }

    public org.thymeleaf.context.Context getJobContext(final Run<?, ?> run, EnvVars environment, int port) {
        org.thymeleaf.context.Context context = new org.thymeleaf.context.Context();
        String pageUrl = Jenkins.get().getRootUrl() + run.getUrl();
        String otlpEndpoint = environment.get("OTEL_EXPORTER_OTLP_ENDPOINT");
        String otlpHeader = environment.get("OTEL_EXPORTER_OTLP_HEADERS");
        String jobName = environment.get("JOB_NAME");
        String jobBaseName = environment.get("JOB_BASE_NAME");
        context.setVariable("JENKINS_URL", Jenkins.get().getRootUrl());
        context.setVariable("pageUrl", pageUrl);
        context.setVariable("env", environment);
        context.setVariable("nePort", port);
        context.setVariable("serviceName", "ci_jemmic_com");
        context.setVariable("jobName", jobName);
        context.setVariable("jobGroupName", trimWithDefault(jobName, jobBaseName, "-"));
        context.setVariable("otlpEndpoint", toOtelCompatibleUrl(otlpEndpoint));
        context.setVariable("otlpAuthHeader", otlpHeader.substring(otlpHeader.indexOf("=") + 1));
        return context;
    }

}
