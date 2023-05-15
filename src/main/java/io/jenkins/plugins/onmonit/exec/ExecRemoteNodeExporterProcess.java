package io.jenkins.plugins.onmonit.exec;

import hudson.AbortException;
import hudson.Proc;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import io.jenkins.plugins.onmonit.util.ComputerInfo;
import org.apache.commons.io.output.TeeOutputStream;
import io.jenkins.plugins.onmonit.LauncherProvider;
import io.jenkins.plugins.onmonit.RemoteProcess;
import hudson.FilePath;
import org.apache.commons.lang.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

public class ExecRemoteNodeExporterProcess implements RemoteProcess {

	protected final LauncherProvider launcherProvider;

	protected final TaskListener listener;

	protected final ComputerInfo info;

	protected final FilePath temp;

	private final Map<String, String> envOverrides;

	protected final String additionalOptions;

	protected final boolean debug;

	protected final int port;

	ExecRemoteNodeExporterProcess(LauncherProvider launcherProvider, TaskListener listener, ComputerInfo info, FilePath temp, String envCookie, String additionalOptions, boolean debug, int port) throws Exception {
		this.launcherProvider = launcherProvider;
		this.listener = listener;
		this.info = info;
		this.temp = temp;
		Map<String, String> overrides = new java.util.HashMap<>();
		overrides.put("_JENKINS_PM_NODE_EXPORTER_COOKIE", envCookie);
		this.envOverrides = overrides;
		this.additionalOptions = additionalOptions;
		this.debug = debug;
		this.port = port;

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ArgumentListBuilder cmd = getCmd();
		if (StringUtils.isNotBlank(additionalOptions)) {
			cmd.addTokenized(additionalOptions);
		}
		cmd.add("--web.disable-exporter-metrics");
		cmd.add("--web.listen-address=:" + port);
		Proc proc = launcherProvider.getLauncher().launch()
				.cmds(cmd)
				.envs(envOverrides)
				.stdout(debug ? new TeeOutputStream(listener.getLogger(), baos) : baos)
				.stderr(debug ? new TeeOutputStream(listener.getLogger(), baos) : baos)
				.start();
		Instant timeout = Instant.now().plus(1, ChronoUnit.MINUTES);
		String strPort = Integer.toString(port);
		while (proc.isAlive() && Instant.now().isBefore(timeout)) {
			String output = baos.toString();
			if (output.contains("Listening on") && output.contains(strPort)) {
				return;
			}
			Thread.sleep(100);
		}
		if (proc.isAlive()) {
			if (!debug) {
				listener.getLogger().println("Failed to start node_exporter, timeout after 1 minute: " + baos);
			}
			throw new AbortException("Failed to start node_exporter, timeout after 1 minute");
		}
		if (!debug) {
			listener.getLogger().println("Failed to start node_exporter: " + baos);
		}
		throw new AbortException("Failed to start node_exporter");
	}

	protected ArgumentListBuilder getCmd() throws IOException, InterruptedException {
		return new ArgumentListBuilder("node_exporter");
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void stop(TaskListener listener) throws IOException, InterruptedException {
		launcherProvider.getLauncher().kill(envOverrides);
	}

}
