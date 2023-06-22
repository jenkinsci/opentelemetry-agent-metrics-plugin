package io.jenkins.plugins.onmonit.exec;

import hudson.AbortException;
import hudson.FilePath;
import hudson.Proc;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import io.jenkins.plugins.onmonit.LauncherProvider;
import io.jenkins.plugins.onmonit.RemoteOtelContribProcess;
import io.jenkins.plugins.onmonit.util.ComputerInfo;
import org.apache.commons.io.output.TeeOutputStream;
import org.apache.commons.lang.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

public class ExecRemoteOtelContribProcess implements RemoteOtelContribProcess {

	public static final String PROC_COOKIE_NAME = "_JENKINS_PM_OTEL_COLLECTOR_COOKIE";

	protected final LauncherProvider launcherProvider;

	protected final TaskListener listener;

	protected final ComputerInfo info;

	protected final FilePath temp;

	protected final Map<String, String> envOverrides;

	protected final String additionalOptions;

	protected final boolean debug;

	private final String configTmpChild;

	private FilePath executableTmpChild;

	private final ArgumentListBuilder cmd;

	private boolean started;

	ExecRemoteOtelContribProcess(LauncherProvider launcherProvider, TaskListener listener, ComputerInfo info, FilePath temp, String envCookie, String additionalOptions, boolean debug) throws Exception {
		this.launcherProvider = launcherProvider;
		this.listener = listener;
		this.info = info;
		this.temp = temp;
		Map<String, String> overrides = new java.util.HashMap<>();
		overrides.put(PROC_COOKIE_NAME, envCookie);
		this.envOverrides = overrides;
		this.additionalOptions = additionalOptions;
		this.debug = debug;
		FilePath configFile = temp.createTempFile("otel", ".yaml");
		this.configTmpChild = configFile.getName();
		this.cmd = getCmd();
		if (StringUtils.isNotBlank(additionalOptions)) {
			cmd.addTokenized(additionalOptions);
		}
		cmd.add("--config=file:" + configFile.getRemote());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void start(TaskListener listener, String config) throws IOException, InterruptedException {
		this.started = true;

		FilePath configFile = temp.child(configTmpChild);
		configFile.write(config, StandardCharsets.UTF_8.name());

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		Proc proc = launcherProvider.getLauncher().launch()
				.cmds(cmd)
				.envs(envOverrides)
				.stdout(debug ? new TeeOutputStream(listener.getLogger(), baos) : baos)
				.stderr(debug ? new TeeOutputStream(listener.getLogger(), baos) : baos)
				.start();
		Instant timeout = Instant.now().plus(1, ChronoUnit.MINUTES);
		while (proc.isAlive() && Instant.now().isBefore(timeout)) {
			String output = baos.toString(StandardCharsets.UTF_8);
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
		return new ArgumentListBuilder("win".equals(info.getOs()) ? "otelcol-contrib.exe" : "otelcol-contrib");
	}

	protected FilePath createTempExecutableFile() throws IOException, InterruptedException {
		FilePath result;
		if ("win".equals(info.getOs())) {
			result = this.temp.createTempFile("otelcol-contrib", "exe");
		} else {
			result = this.temp.createTempFile("otelcol-contrib", "");
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
		temp.child(configTmpChild).delete();
		if (executableTmpChild != null) {
			executableTmpChild.delete();
		}
	}

}
