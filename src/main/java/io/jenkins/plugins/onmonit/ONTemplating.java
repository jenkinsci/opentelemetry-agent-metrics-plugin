package io.jenkins.plugins.onmonit;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

public class ONTemplating {
    //private String configTemplate = null;
    private TemplateEngine templateEngine = null;

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
        final TemplateEngine templateEngine = new TemplateEngine();
        templateEngine.setTemplateResolver(templateResolver);
        this.templateEngine = templateEngine;
        return this.templateEngine;
    }

    String renderTemplate(Context context) {
        return getTemplateEngine().process("otel.yaml", context);
    }
}
