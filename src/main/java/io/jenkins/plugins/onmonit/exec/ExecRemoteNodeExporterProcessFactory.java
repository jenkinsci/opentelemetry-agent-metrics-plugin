package io.jenkins.plugins.onmonit.exec;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import io.jenkins.plugins.onmonit.LauncherProvider;
import io.jenkins.plugins.onmonit.RemoteNodeExporterProcess;
import io.jenkins.plugins.onmonit.RemoteNodeExporterProcessFactory;
import io.jenkins.plugins.onmonit.util.ComputerInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
		return "Exec node_exporter (binary node_exporter present on remote machine)";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isSupported(Launcher launcher, final TaskListener listener, ComputerInfo info) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			String cmd = "win".equals(info.getOs()) ? "windows_exporter.exe" : "node_exporter";
			int status = launcher.launch().cmds(cmd, "--version").quiet(true).stdout(baos).stderr(baos).start().joinWithTimeout(1, TimeUnit.MINUTES, listener);
			String version = baos.toString(StandardCharsets.UTF_8);
			/*
			 * `node_exporter --version` should always return 0. For the moment we explicitly require version 1.5.0
			 */
			String expectedVersion = "win".equals(info.getOs()) ? "version 0.22.0" : "version 1.5.0";
			if (status == 0 && version.contains(expectedVersion)) {
				return true;
			}
			listener.getLogger().println("Unsupported, requiring " + expectedVersion + ": `node_exporter --version` returned " + status + " printed " + version);
			return false;
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
	public RemoteNodeExporterProcess create(LauncherProvider launcherProvider, TaskListener listener, ComputerInfo info,
											@CheckForNull FilePath temp, String envCookie, String additionalOptions,
											boolean debug)
			throws Throwable {
		return new ExecRemoteNodeExporterProcess(launcherProvider, listener, info, temp, envCookie, additionalOptions, debug);
	}
}