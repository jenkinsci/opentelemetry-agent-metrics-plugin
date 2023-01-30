package io.jenkins.plugins.onmonit;

import java.io.Serializable;

import hudson.model.InvisibleAction;

public class ONMonitoringEnvironment extends InvisibleAction implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Remote path of the opentelemetry config dir */
    public final String configDir;

    /** Port used */
    public final int port;

    /** The shutdownWithBuild indicator from the job configuration. */
    public final boolean shutdownWithBuild;

    /** Random value identifying the Node exporter process. */
    public String neCookie;

    /** Random value identifying the Otel collector process. */
    public String ocCookie;

    public ONMonitoringEnvironment(final String neCookie, final String ocCookie, final String configDir, final int port, final boolean shutdownWithBuild) {
        this.neCookie = neCookie;
        this.ocCookie = ocCookie;
        this.configDir = configDir;
        this.port = port;
        this.shutdownWithBuild = shutdownWithBuild;
    }

}
