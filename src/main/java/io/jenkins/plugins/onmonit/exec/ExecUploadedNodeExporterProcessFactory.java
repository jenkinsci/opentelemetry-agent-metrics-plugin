package io.jenkins.plugins.onmonit.exec;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import io.jenkins.plugins.onmonit.LauncherProvider;
import io.jenkins.plugins.onmonit.RemoteNodeExporterProcessFactory;
import io.jenkins.plugins.onmonit.RemoteOtelContribProcessFactory;
import io.jenkins.plugins.onmonit.RemoteProcess;
import io.jenkins.plugins.onmonit.util.ComputerInfo;

/**
 * A factory that uses a node_exporter binary uploaded to a remote system.
 */
@Extension
public class ExecUploadedNodeExporterProcessFactory extends RemoteNodeExporterProcessFactory {

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getDisplayName() {
		return "Exec node_exporter (uploaded node_exporter to remote machine)";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isSupported(Launcher launcher, final TaskListener listener, ComputerInfo info) {
		// TODO: perform better check for actually supported systems
		return true;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public RemoteProcess start(LauncherProvider launcherProvider, final TaskListener listener, ComputerInfo info, FilePath temp, String envCookie, String additionalOptions, boolean debug, int port)
			throws Throwable {
		return new ExecUploadedNodeExporterProcess(launcherProvider, listener, info, temp, envCookie, additionalOptions, debug, port);
	}
}