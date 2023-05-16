package io.jenkins.plugins.onmonit.exec;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import io.jenkins.plugins.onmonit.LauncherProvider;
import io.jenkins.plugins.onmonit.RemoteNodeExporterProcessFactory;
import io.jenkins.plugins.onmonit.RemoteProcess;
import io.jenkins.plugins.onmonit.util.ComputerInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * A factory that uses the native node-exporter installed on a remote system. node-exporter has to be in PATH environment variable.
 */
public class ExecRemoteNodeExporterProcessFactory extends RemoteNodeExporterProcessFactory {

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getDisplayName() {
		return "Exec node_exporter (binary node_exporter on a remote machine)";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isSupported(Launcher launcher, final TaskListener listener, ComputerInfo info) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			int status = launcher.launch().cmds("node_exporter", "--version").quiet(true).stdout(baos).stderr(baos).start().joinWithTimeout(1, TimeUnit.MINUTES, listener);
			String version = baos.toString();
			/*
			 * `node_exporter --version` should always return 0. For the moment we explicitly require version 1.5.0
			 */
			return status == 0 && version.contains("version 1.5.0");
		} catch (IOException e) {
			listener.getLogger().println("Could not find node_exporter: IOException: " + e.getMessage());
			listener.getLogger().println("Check if node_exporter is installed and in PATH");
			return false;
		} catch (InterruptedException e) {
			e.printStackTrace();
			listener.getLogger().println("Could not find node_exporter: InterruptedException: " + e.getMessage());
			return false;
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public RemoteProcess start(LauncherProvider launcherProvider, TaskListener listener, ComputerInfo info,
							   @CheckForNull FilePath temp, String envCookie, String additionalOptions,
							   boolean debug, int port)
			throws Throwable {
		return new ExecRemoteNodeExporterProcess(launcherProvider, listener, info, temp, envCookie, additionalOptions, debug, port);
	}
}