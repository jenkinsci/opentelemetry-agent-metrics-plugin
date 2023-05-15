package io.jenkins.plugins.onmonit.exec;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import io.jenkins.plugins.onmonit.LauncherProvider;
import io.jenkins.plugins.onmonit.RemoteNodeExporterProcessFactory;
import io.jenkins.plugins.onmonit.RemoteProcess;
import io.jenkins.plugins.onmonit.util.ComputerInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A factory that delegates to other factories for node_exporter execution.
 */
//@Extension
public class DelegatedNodeExporterProcessFactory extends RemoteNodeExporterProcessFactory {

	private static final List<RemoteNodeExporterProcessFactory> delegates;

	static {
		List<RemoteNodeExporterProcessFactory> d = new ArrayList<>();
		d.add(new ExecRemoteNodeExporterProcessFactory());
		d.add(new ExecUploadedNodeExporterProcessFactory());
		delegates = Collections.unmodifiableList(d);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getDisplayName() {
		return "Delegated exec node_exporter (fixed order delegation to other process factories)";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isSupported(Launcher launcher, final TaskListener listener, ComputerInfo info) {
		/*for (RemoteNodeExporterProcessFactory factory : delegates) {
			if (factory.isSupported(launcher, listener, info)) {
				return true;
			}
		}*/
		// TODO: perform better check for actually supported systems
		return true;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public RemoteProcess start(LauncherProvider launcherProvider, final TaskListener listener, ComputerInfo info, FilePath temp, String envCookie, String additionalOptions, boolean debug, int port)
			throws Throwable {
		Launcher launcher = launcherProvider.getLauncher();
		Map<String, Throwable> faults = new LinkedHashMap<>();
		for (RemoteNodeExporterProcessFactory factory : delegates) {
			if (factory.isSupported(launcher, listener, info)) {
				try {
					listener.getLogger().println("[on-monit]   " + factory.getDisplayName());
					return factory.start(launcherProvider, listener, info, temp, envCookie, additionalOptions, debug, port);
				} catch (Throwable t) {
					faults.put(factory.getDisplayName(), t);
				}
			}
		}
		throw new RuntimeException("Could not start process");
	}
}