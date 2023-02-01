package io.jenkins.plugins.onmonit;

import java.io.IOException;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.tasks.SimpleBuildWrapper.Disposer;

public class ONDisposer extends Disposer {

    private static final long serialVersionUID = 1L;

    private final ONMonitoringEnvironment onEnvironment;

    public ONDisposer(final ONMonitoringEnvironment onEnvironment) {
        this.onEnvironment = onEnvironment;
    }

    @Override
    public void tearDown(final Run<?, ?> run, final FilePath workspace, final Launcher launcher, final TaskListener listener) throws IOException, InterruptedException {
        if (!onEnvironment.shutdownWithBuild) {
            ONMonitoring.shutdownAndCleanup(onEnvironment, launcher, listener);
        }
    }

}
