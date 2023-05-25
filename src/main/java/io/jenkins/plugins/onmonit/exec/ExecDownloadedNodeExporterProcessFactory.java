package io.jenkins.plugins.onmonit.exec;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import io.jenkins.plugins.onmonit.LauncherProvider;
import io.jenkins.plugins.onmonit.ONMonitConfig;
import io.jenkins.plugins.onmonit.RemoteNodeExporterProcess;
import io.jenkins.plugins.onmonit.RemoteNodeExporterProcessFactory;
import io.jenkins.plugins.onmonit.util.ComputerInfo;

/**
 * A factory that uses a node_exporter binary uploaded to a remote system.
 */
public class ExecDownloadedNodeExporterProcessFactory extends RemoteNodeExporterProcessFactory {

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getDisplayName() {
		return "Exec node_exporter (download node_exporter from web to remote machine)";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isSupported(Launcher launcher, final TaskListener listener, ComputerInfo info) {
		if (ONMonitConfig.get().getDownloadBaseUrl().isEmpty()) {
			return false;
		}
		// TODO: perform better check for actually supported systems
		return true;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public RemoteNodeExporterProcess create(LauncherProvider launcherProvider, final TaskListener listener, ComputerInfo info, FilePath temp, String envCookie, String additionalOptions, boolean debug)
			throws Throwable {
		return new ExecDownloadedNodeExporterProcess(launcherProvider, listener, info, temp, envCookie, additionalOptions, debug);
	}
}