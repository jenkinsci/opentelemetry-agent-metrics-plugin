package io.jenkins.plugins.prometheusmonit;

import java.io.Serializable;

import hudson.model.InvisibleAction;

public class PrometheusMonitoringEnvironment extends InvisibleAction implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Remote path of the opentelemetry config dir */
    public final String configDir;

    /** Port used */
    public final int port;

    /** The shutdownWithBuild indicator from the job configuration. */
    public final boolean shutdownWithBuild;

    /** Random value identifying the Node exporter process. */
    public String cookie;

    public PrometheusMonitoringEnvironment(final String cookie, final String configDir, final int port, final boolean shutdownWithBuild) {
        this.cookie = cookie;
        this.configDir = configDir;
        this.port = port;
        this.shutdownWithBuild = shutdownWithBuild;
    }

}
