package io.jenkins.plugins.onmonit.exec;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import io.jenkins.plugins.onmonit.LauncherProvider;
import io.jenkins.plugins.onmonit.ONMonitConfig;
import io.jenkins.plugins.onmonit.RemoteOtelContribProcessFactory;
import io.jenkins.plugins.onmonit.RemoteProcess;
import io.jenkins.plugins.onmonit.util.ComputerInfo;

/**
 * A factory that uses an otelcol-contrib binary uploaded to a remote system.
 */
public class ExecDownloadedOtelContribProcessFactory extends RemoteOtelContribProcessFactory {

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getDisplayName() {
		return "Exec otelcol-contrib (download otelcol-contrib from web to remote machine)";
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
	public RemoteProcess start(LauncherProvider launcherProvider, final TaskListener listener, ComputerInfo info, FilePath temp, String envCookie, String additionalOptions, boolean debug, String config)
			throws Throwable {
		return new ExecDownloadedOtelContribProcess(launcherProvider, listener, info, temp, envCookie, additionalOptions, debug, config);
	}
}