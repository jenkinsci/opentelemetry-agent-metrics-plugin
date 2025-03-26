package io.jenkins.plugins.onmonit;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.Serializable;

public class ONMonitoringStep extends Step implements Serializable {

	private static final long serialVersionUID = -8426358446277313998L;

	/**
	 * The port for the node-exporter to listen on.
	 */
	private int port = 9100;

	/**
	 * Whether to print debug messages to the job console log.
	 */
	private boolean debug = false;

	/**
	 * Whether to launch node_exporter + otelcol-contrib processes.
	 */
	private boolean launchCollector = true;

	/**
	 * Override the global dashboard URL.
	 */
	private String dashboardUrl = null;

	/**
	 * Any additional options to set for the node_exporter process.
	 */
	private String neAdditionalOptions = "";

	/**
	 * Any additional options to set for the otel-contrib process.
	 */
	private String ocAdditionalOptions = "";

	/** Constructor. */
	@DataBoundConstructor
	public ONMonitoringStep() {}

	@Override
	public StepExecution start(StepContext context) throws Exception {
		return new ONMonitoringStepExecution(context, port, debug, launchCollector, dashboardUrl, neAdditionalOptions, ocAdditionalOptions);
	}

	@DataBoundSetter
	public void setPort(final int port) {
		this.port = port;
	}

	@DataBoundSetter
	public void setDebug(final boolean debug) {
		this.debug = debug;
	}

	@DataBoundSetter
	public void setLaunchCollector(final boolean launchCollector) {
		this.launchCollector = launchCollector;
	}

	@DataBoundSetter
	public void setDashboardUrl(final String dashboardUrl) {
		this.dashboardUrl = dashboardUrl;
	}

	@DataBoundSetter
	public void setNeAdditionalOptions(final String neAdditionalOptions) {
		this.neAdditionalOptions = neAdditionalOptions;
	}

	@DataBoundSetter
	public void setOcAdditionalOptions(final String ocAdditionalOptions) {
		this.ocAdditionalOptions = ocAdditionalOptions;
	}

	public int getPort() {
		return port;
	}

	public boolean isDebug() {
		return debug;
	}

	public boolean isLaunchCollector() {
		return launchCollector;
	}

	public String getDashboardUrl() {
		return dashboardUrl;
	}

	public String getNeAdditionalOptions() {
		return neAdditionalOptions;
	}

	public String getOcAdditionalOptions() {
		return ocAdditionalOptions;
	}

	@Extension
	public static final class DescriptorImpl extends AbstractStepDescriptorImpl {

		public DescriptorImpl() {
			super(ONMonitoringStepExecution.class);
		}

		@Override
		public String getFunctionName() {
			return "onMonit";
		}

		@NonNull
		@Override
		public String getDisplayName() {
			return Messages.ONMonitoringStep_DisplayName();
		}

		@Override
		public boolean takesImplicitBlockArgument() {
			return true;
		}

	}

}
