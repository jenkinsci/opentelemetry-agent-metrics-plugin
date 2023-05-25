package io.jenkins.plugins.onmonit;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.ExtensionPoint;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import io.jenkins.plugins.onmonit.util.ComputerInfo;

/**
 * Extension point for otel-contrib providers.
 */
public abstract class RemoteOtelContribProcessFactory implements ExtensionPoint {

	/**
	 * The display name of the factory.
	 *
	 * @return The display name of the factory.
	 */
	public abstract String getDisplayName();

	/**
	 * Checks if the supplied launcher is supported by this factory.
	 *
	 * @param launcher the launcher on which the factory would be asked to start an otel-contrib process.
	 * @param listener a listener in case any user diagnostics are to be printed.
	 * @param info information about the target environment (OS, arch)
	 * @return {@code false} if the factory does not want to try and start a node-exporter on the launcher.
	 */
	public abstract boolean isSupported(Launcher launcher, TaskListener listener, ComputerInfo info);

	/**
	 * Create an otel-contrib process on the specified launcher. This does not yet start it.
	 *
	 * @param launcherProvider provides launchers on which to start an otel-contrib process.
	 * @param listener a listener for any diagnostics.
	 * @param info information about the target environment (OS, arch)
	 * @param temp a temporary directory to use; null if unspecified
	 * @param envCookie a value to distinguish the created process
	 * @param additionalOptions any additional arguments to pass to the launched process
	 * @param debug whether to pass any process output to the Job console log (useful for troubleshooting)
	 * @return the process.
	 * @throws Throwable if the process cannot be started.
	 */
	public abstract RemoteOtelContribProcess create(LauncherProvider launcherProvider, TaskListener listener, ComputerInfo info,
										 @CheckForNull FilePath temp, String envCookie, String additionalOptions,
										 boolean debug) throws Throwable;

}
