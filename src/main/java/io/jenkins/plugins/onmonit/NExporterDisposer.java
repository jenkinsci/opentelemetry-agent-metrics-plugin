package io.jenkins.plugins.onmonit;

import java.io.IOException;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.tasks.SimpleBuildWrapper.Disposer;

public class NExporterDisposer extends Disposer {

    private static final long serialVersionUID = 1L;

    private final ONMonitoringEnvironment pmEnvironment;

    public NExporterDisposer(final ONMonitoringEnvironment pmEnvironment) {
        this.pmEnvironment = pmEnvironment;
    }

    @Override
    public void tearDown(final Run<?, ?> run, final FilePath workspace, final Launcher launcher, final TaskListener listener) throws IOException, InterruptedException {
        if (!pmEnvironment.shutdownWithBuild) {
            ONMonitoring.shutdownAndCleanup(pmEnvironment, launcher, listener);
        }
    }

}
