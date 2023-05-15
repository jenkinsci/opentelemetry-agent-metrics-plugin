package io.jenkins.plugins.onmonit.exec;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import io.jenkins.plugins.onmonit.LauncherProvider;
import io.jenkins.plugins.onmonit.RemoteOtelContribProcessFactory;
import io.jenkins.plugins.onmonit.RemoteProcess;
import io.jenkins.plugins.onmonit.util.ComputerInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * A factory that uses the native otelcol-contrib installed on a remote system. otelcol-contrib has to be in PATH environment variable.
 */
@Extension
public class ExecRemoteOtelContribProcessFactory extends RemoteOtelContribProcessFactory {

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getDisplayName() {
		return "Exec otelcol-contrib (binary otelcol-contrib on a remote machine)";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isSupported(Launcher launcher, final TaskListener listener, ComputerInfo info) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			int status = launcher.launch().cmds("otelcol-contrib", "--version").quiet(true).stdout(baos).stderr(baos).start().joinWithTimeout(1, TimeUnit.MINUTES, listener);
			String version = baos.toString();
			/*
			 * `otelcol-contrib --version` should always return 0. For the moment we explicitly require version 0.70.0
			 */
			if (status == 0 && version.contains("version 0.70.0")) {
				return true;
			}
			listener.getLogger().println("Unsupported, requiring version 0.70.0: `otelcol-contrib --version` returned " + status + " printed " + version);
			return false;
		} catch (IOException e) {
			e.printStackTrace();
			listener.getLogger().println("Could not find otelcol-contrib: IOException: " + e.getMessage());
			listener.getLogger().println("Check if otelcol-contrib is installed and in PATH");
			return false;
		} catch (InterruptedException e) {
			e.printStackTrace();
			listener.getLogger().println("Could not find otelcol-contrib: InterruptedException: " + e.getMessage());
			return false;
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public RemoteProcess start(LauncherProvider launcherProvider, final TaskListener listener, ComputerInfo info, FilePath temp, String envCookie, String additionalOptions, boolean debug, String config)
			throws Throwable {
		return new ExecRemoteOtelContribProcess(launcherProvider, listener, info, temp, envCookie, additionalOptions, debug, config);
	}
}