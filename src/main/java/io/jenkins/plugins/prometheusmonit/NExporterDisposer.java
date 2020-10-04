package io.jenkins.plugins.prometheusmonit;

import java.io.IOException;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.tasks.SimpleBuildWrapper.Disposer;

public class NExporterDisposer extends Disposer {

    private static final long serialVersionUID = 1L;

    private final PrometheusMonitoringEnvironment pmEnvironment;

    public NExporterDisposer(final PrometheusMonitoringEnvironment pmEnvironment) {
        this.pmEnvironment = pmEnvironment;
    }

    @Override
    public void tearDown(final Run<?, ?> run, final FilePath workspace, final Launcher launcher, final TaskListener listener) throws IOException, InterruptedException {
        if (!pmEnvironment.shutdownWithBuild) {
            PrometheusMonitoring.shutdownAndCleanup(pmEnvironment, launcher, listener);
        }
    }

}
