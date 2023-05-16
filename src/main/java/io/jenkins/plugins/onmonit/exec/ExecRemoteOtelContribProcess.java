package io.jenkins.plugins.onmonit.exec;

import hudson.AbortException;
import hudson.FilePath;
import hudson.Proc;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import io.jenkins.plugins.onmonit.LauncherProvider;
import io.jenkins.plugins.onmonit.RemoteProcess;
import io.jenkins.plugins.onmonit.util.ComputerInfo;
import org.apache.commons.io.output.TeeOutputStream;
import org.apache.commons.lang.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

public class ExecRemoteOtelContribProcess implements RemoteProcess {

	public static final String PROC_COOKIE_NAME = "_JENKINS_PM_OTEL_COLLECTOR_COOKIE";

	protected final LauncherProvider launcherProvider;

	protected final TaskListener listener;

	protected final ComputerInfo info;

	protected final FilePath temp;

	protected final Map<String, String> envOverrides;

	protected final String additionalOptions;

	protected final boolean debug;

	private final String config;

	private final String configTmpChild;

	ExecRemoteOtelContribProcess(LauncherProvider launcherProvider, TaskListener listener, ComputerInfo info, FilePath temp, String envCookie, String additionalOptions, boolean debug, String config) throws Exception {
		this.launcherProvider = launcherProvider;
		this.listener = listener;
		this.info = info;
		this.temp = temp;
		Map<String, String> overrides = new java.util.HashMap<>();
		overrides.put(PROC_COOKIE_NAME, envCookie);
		this.envOverrides = overrides;
		this.additionalOptions = additionalOptions;
		this.debug = debug;
		this.config = config;

		FilePath configFile = temp.createTempFile("otel", ".yaml");
		configFile.write(config, StandardCharsets.UTF_8.name());
		this.configTmpChild = configFile.getName();

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ArgumentListBuilder cmd = getCmd();
		if (StringUtils.isNotBlank(additionalOptions)) {
			cmd.addTokenized(additionalOptions);
		}
		cmd.add("--config=file:" + configFile.getRemote());
		Proc proc = launcherProvider.getLauncher().launch()
				.cmds(cmd)
				.envs(envOverrides)
				.stdout(debug ? new TeeOutputStream(listener.getLogger(), baos) : baos)
				.stderr(debug ? new TeeOutputStream(listener.getLogger(), baos) : baos)
				.start();
		Instant timeout = Instant.now().plus(1, ChronoUnit.MINUTES);
		while (proc.isAlive() && Instant.now().isBefore(timeout)) {
			String output = baos.toString();
			if (output.contains("Everything is ready.")) {
				return;
			}
			Thread.sleep(100);
		}
		if (proc.isAlive()) {
			if (!debug) {
				listener.getLogger().println("Failed to start otelcol-contrib, timeout after 1 minute: " + baos);
			}
			throw new AbortException("Failed to start otelcol-contrib, timeout after 1 minute");
		}
		if (!debug) {
			listener.getLogger().println("Failed to start otelcol-contrib: " + baos);
		}
		throw new AbortException("Failed to start otelcol-contrib");
	}

	protected ArgumentListBuilder getCmd() throws IOException, InterruptedException {
		return new ArgumentListBuilder("otelcol-contrib");
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void stop(TaskListener listener) throws IOException, InterruptedException {
		launcherProvider.getLauncher().kill(envOverrides);
		temp.child(configTmpChild).delete();
	}

}
