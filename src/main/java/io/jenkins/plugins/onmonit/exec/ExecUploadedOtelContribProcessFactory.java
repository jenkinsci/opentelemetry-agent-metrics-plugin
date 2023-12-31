package io.jenkins.plugins.onmonit.exec;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import io.jenkins.plugins.onmonit.LauncherProvider;
import io.jenkins.plugins.onmonit.RemoteOtelContribProcess;
import io.jenkins.plugins.onmonit.RemoteOtelContribProcessFactory;
import io.jenkins.plugins.onmonit.util.ComputerInfo;

/**
 * A factory that uses an otelcol-contrib binary uploaded to a remote system.
 */
public class ExecUploadedOtelContribProcessFactory extends RemoteOtelContribProcessFactory {

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getDisplayName() {
		return "Exec otelcol-contrib (uploaded otelcol-contrib from master to remote machine)";
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
	public RemoteOtelContribProcess create(LauncherProvider launcherProvider, final TaskListener listener, ComputerInfo info, FilePath temp, String envCookie, String additionalOptions, boolean debug)
			throws Throwable {
		return new ExecUploadedOtelContribProcess(launcherProvider, listener, info, temp, envCookie, additionalOptions, debug);
	}
}