package io.jenkins.plugins.onmonit.exec;

import hudson.AbortException;
import hudson.Proc;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import io.jenkins.plugins.onmonit.RemoteNodeExporterProcess;
import io.jenkins.plugins.onmonit.util.ComputerInfo;
import org.apache.commons.io.output.TeeOutputStream;
import io.jenkins.plugins.onmonit.LauncherProvider;
import hudson.FilePath;
import org.apache.commons.lang.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

public class ExecRemoteNodeExporterProcess implements RemoteNodeExporterProcess {

	public static final String PROC_COOKIE_NAME = "_JENKINS_PM_NODE_EXPORTER_COOKIE";

	protected final LauncherProvider launcherProvider;

	protected final TaskListener listener;

	protected final ComputerInfo info;

	protected final FilePath temp;

	private final Map<String, String> envOverrides;

	protected final String additionalOptions;

	protected final boolean debug;

	private FilePath executableTmpChild;

	private final ArgumentListBuilder cmd;

	private boolean started;

	ExecRemoteNodeExporterProcess(LauncherProvider launcherProvider, TaskListener listener, ComputerInfo info, FilePath temp, String envCookie, String additionalOptions, boolean debug) throws Exception {
		this.launcherProvider = launcherProvider;
		this.listener = listener;
		this.info = info;
		this.temp = temp;
		Map<String, String> overrides = new java.util.HashMap<>();
		overrides.put(PROC_COOKIE_NAME, envCookie);
		this.envOverrides = overrides;
		this.additionalOptions = additionalOptions;
		this.debug = debug;
		this.cmd = getCmd();
		if (StringUtils.isNotBlank(additionalOptions)) {
			cmd.addTokenized(additionalOptions);
		}
		cmd.add("--web.disable-exporter-metrics");
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void start(TaskListener listener, int port) throws IOException, InterruptedException {
		this.started = true;
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
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
		return new ArgumentListBuilder("win".equals(info.getOs()) ? "windows_exporter.exe" : "node_exporter");
	}

	protected FilePath createTempExecutableFile() throws IOException, InterruptedException {
		FilePath result;
		if ("win".equals(info.getOs())) {
			result = this.temp.createTempFile("windows_exporter", "exe");
		} else {
			result = this.temp.createTempFile("node_exporter", "");
		}
		this.executableTmpChild = result;
		return result;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void stop(TaskListener listener) throws IOException, InterruptedException {
		if (started) {
			launcherProvider.getLauncher().kill(envOverrides);
			started = false;
		}
		if (executableTmpChild != null) {
			executableTmpChild.delete();
		}
	}

}
