package io.jenkins.plugins.onmonit.exec;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import io.jenkins.plugins.onmonit.LauncherProvider;
import io.jenkins.plugins.onmonit.RemoteOtelContribProcess;
import io.jenkins.plugins.onmonit.RemoteOtelContribProcessFactory;
import io.jenkins.plugins.onmonit.util.ComputerInfo;
import org.apache.commons.lang.StringUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A factory that delegates to other factories for otelcol-contrib execution.
 */
@Extension
public class DelegatedOtelContribProcessFactory extends RemoteOtelContribProcessFactory {

	private static final List<RemoteOtelContribProcessFactory> delegates;

	static {
		delegates = List.of(new ExecRemoteOtelContribProcessFactory(), new ExecDownloadedOtelContribProcessFactory(), new ExecUploadedOtelContribProcessFactory());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getDisplayName() {
		return "Delegated exec otelcol-contrib (fixed order delegation to other process factories)";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isSupported(Launcher launcher, final TaskListener listener, ComputerInfo info) {
		/*for (RemoteOtelContribProcessFactory factory : delegates) {
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
	public RemoteOtelContribProcess create(LauncherProvider launcherProvider, final TaskListener listener, ComputerInfo info, FilePath temp, String envCookie, String additionalOptions, boolean debug)
			throws Throwable {
		Launcher launcher = launcherProvider.getLauncher();
		Map<String, Throwable> faults = new LinkedHashMap<>();
		for (RemoteOtelContribProcessFactory factory : delegates) {
			if (factory.isSupported(launcher, listener, info)) {
				try {
					listener.getLogger().println("[on-monit]   " + factory.getDisplayName());
					return factory.create(launcherProvider, listener, info, temp, envCookie, additionalOptions, debug);
				} catch (Throwable t) {
					faults.put(factory.getDisplayName(), t);
				}
			}
		}
		for (Map.Entry<String, Throwable> fault : faults.entrySet()) {
			listener.getLogger().println("[on-monit] * " + fault.getKey());
			StringWriter sw = new StringWriter();
			fault.getValue().printStackTrace(new PrintWriter(sw));
			for (String line : StringUtils.split(sw.toString(), "\n")) {
				listener.getLogger().println("[on-monit]     " + line);
			}
		}
		throw new RuntimeException("Could not start process otel-contrib");
	}
}