package io.jenkins.plugins.onmonit;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.ExtensionPoint;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;

/**
 * Extension point for node-exporter providers.
 */
public abstract class RemoteNodeExporterProcessFactory implements ExtensionPoint {

	/**
	 * The display name of the factory.
	 *
	 * @return The display name of the factory.
	 */
	public abstract String getDisplayName();

	/**
	 * Checks if the supplied launcher is supported by this factory.
	 *
	 * @param launcher the launcher on which the factory would be asked to start a node-exporter.
	 * @param listener a listener in case any user diagnostics are to be printed.
	 * @return {@code false} if the factory does not want to try and start a node-exporter on the launcher.
	 */
	public abstract boolean isSupported(Launcher launcher, TaskListener listener);

	/**
	 * Start a node-exporter process on the specified launcher.
	 *
	 * @param launcherProvider provides launchers on which to start a node-exporter.
	 * @param listener a listener for any diagnostics.
	 * @param temp a temporary directory to use; null if unspecified
	 * @return the process.
	 * @throws Throwable if the process cannot be started.
	 */
	public abstract RemoteProcess start(LauncherProvider launcherProvider, TaskListener listener,
										@CheckForNull FilePath temp, String envCookie, String additionalOptions,
										boolean debug, int port) throws Throwable;

}
