package io.jenkins.plugins.onmonit;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.model.TaskListener;
import io.jenkins.plugins.onmonit.exec.ExecRemoteNodeExporterProcess;
import io.jenkins.plugins.onmonit.exec.ExecRemoteOtelContribProcess;
import io.jenkins.plugins.onmonit.util.AvailablePortRetriever;
import io.jenkins.plugins.onmonit.util.ComputerInfo;
import io.jenkins.plugins.onmonit.util.RemoteComputerInfoRetriever;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.GeneralNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.slaves.WorkspaceList;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serial;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Starts node_exporter and otel-contrib, runs the body, and stops the processes again.
 *
 * <p>Detecting the agent, transferring binaries and waiting for both processes to become ready can take many seconds,
 * so it runs on a worker thread ({@link GeneralNonBlockingStepExecution}) rather than the CPS VM thread, which would
 * otherwise block every other {@code parallel} branch of the build (#126). {@link #stop(Throwable)} may therefore run
 * concurrently with a startup in progress; see {@link #cleanUp()}.
 */
public class ONMonitoringStepExecution extends GeneralNonBlockingStepExecution implements LauncherProvider {

	@Serial
	private static final long serialVersionUID = 1L;

	private int port;

	private boolean debug;

	private boolean launchCollector;

	private String dashboardUrl;

	private String neAdditionalOptions;

	private String ocAdditionalOptions;

	private String neCookie;

	private String ocCookie;

	private static final Logger LOGGER = Logger.getLogger(ONMonitoringStepExecution.class.getName());

	/** How often to retry starting node_exporter on another port after its port was taken. */
	private static final int PORT_RETRIES = 3;

	private static ONTemplating templating = new ONTemplating();

	/**
	 * The proxy for the real remote node_exporter process that is on the other side of the channel (as the process needs to
	 * run on a remote machine)
	 */
	private transient volatile RemoteNodeExporterProcess nodeExporter = null;

	/**
	 * The proxy for the real remote otel-contrib process that is on the other side of the channel (as the process needs to
	 * run on a remote machine)
	 */
	private transient volatile RemoteOtelContribProcess otelContrib = null;

	/**
	 * Set by {@link #stop(Throwable)} before it interrupts a startup in progress. A startup that finishes launching
	 * after this is set cleans up itself instead of running the body.
	 */
	private transient volatile boolean stopping;

	ONMonitoringStepExecution(StepContext context, int port, boolean debug, boolean launchCollector, String dashboardUrl, String neAdditionalOptions, String ocAdditionalOptions) {
		super(context);
		this.port = port;
		this.debug = debug;
		this.launchCollector = launchCollector;
		this.dashboardUrl = dashboardUrl;
		this.neAdditionalOptions = neAdditionalOptions;
		this.ocAdditionalOptions = ocAdditionalOptions;
		this.neCookie = UUID.randomUUID().toString();
		this.ocCookie = UUID.randomUUID().toString();
	}

	@Override
	public boolean start() throws Exception {
		run(this::doStart);
		return false;
	}

	/**
	 * Runs on a worker thread: starts the processes, then the body. On failure the processes started so far are
	 * stopped and the exception is rethrown, so that {@link GeneralNonBlockingStepExecution} fails the step.
	 */
	private void doStart() throws Exception {
		try {
			initRemoteProcesses();
			addActionOnce();
			// Check and start under the lock stop() sets the flag with, so a concurrent stop() either prevents the
			// body from starting or comes after it. Starting the body only queues it on the CPS VM thread.
			synchronized (this) {
				if (!stopping) {
					getContext().newBodyInvoker().
							withCallback(new Callback(this)).start();
					return;
				}
			}
			throw new InterruptedException("onMonit was stopped while starting");
		} catch (Exception e) {
			cleanUpAfterFailedStart();
			throw e;
		}
	}

	private void cleanUpAfterFailedStart() {
		// An abort interrupts this thread; clear the flag so that the remote kill calls are not cut short.
		boolean interrupted = Thread.interrupted();
		try {
			getListener().getLogger().println(Messages.ONMonitoringStep_CouldNotStartProcesses());
			cleanUp();
		} catch (Exception x) {
			if (x instanceof InterruptedException) {
				interrupted = true;
			}
			LOGGER.log(Level.WARNING, "Could not clean up after a failed onMonit start", x);
		} finally {
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}

	@Override
	public void stop(@NonNull Throwable cause) throws Exception {
		synchronized (this) {
			stopping = true;
		}
		try {
			// Interrupts a startup in progress (which then cleans up itself as well) and fails the step.
			super.stop(cause);
		} finally {
			cleanUp();
		}
	}

	@Override
	public void onResume() {
		// The base class fails the step if Jenkins went down while the processes were still being started.
		boolean wasStarting = blocksRestart();
		super.onResume();
		if (wasStarting) {
			run(this::cleanUp);
			return;
		}
		// The body is still running: restart the processes, which did not survive the restart of the controller.
		run(() -> {
			try {
				cleanUp();
				initRemoteProcesses();
			} catch (InterruptedException e) {
				throw e;
			} catch (Exception x) {
				x.printStackTrace(getListener().getLogger());
				getListener().getLogger().println(Messages.ONMonitoringStep_CouldNotStartProcesses());
				throw x;
			}
		});
	}

	private static class Callback extends BodyExecutionCallback.TailCall {

		@Serial
		private static final long serialVersionUID = 1L;

		private final ONMonitoringStepExecution execution;

		Callback (ONMonitoringStepExecution execution) {
			this.execution = execution;
		}

		@Override
		protected void finished(StepContext context) throws Exception {
			execution.cleanUp();
		}

	}

	static FilePath tempDir(FilePath ws) throws IOException, InterruptedException {
		FilePath tmpDir = WorkspaceList.tempDir(ws);
		if (tmpDir == null) {
			throw new IOException("Could not create a tempDirectory in " + ws);
		}
		tmpDir.mkdirs();
		return tmpDir;
	}

	/**
	 * Initializes the node_exporter and otel-contrib processes.
	 *
	 * @throws IOException
	 */
	private void initRemoteProcesses() throws IOException, InterruptedException {
		TaskListener listener = getListener();
		if (!launchCollector) {
			listener.getLogger().println("[on-monit] Skipping launch of node_exporter and otel-contrib processes.");
			return;
		}

		// TODO UI could be streamlined by unifying implementation (trying remote and fallback to upload)
		Run<?, ?> build = getBuild();
		FilePath workspace = getWorkspace();
		Launcher launcher = getLauncher();
		ComputerInfo info = RemoteComputerInfoRetriever.getRemoteInfo(launcher);
		Semaphore portSync = AvailablePortRetriever.getSyncOjbectForLauncher(launcher);
		Map<String, Throwable> faults = new LinkedHashMap<>();
		// The fields let stop() find the processes; stop() may also clear them concurrently, so use these locals here.
		RemoteNodeExporterProcess ne = null;
		RemoteOtelContribProcess oc = null;
		listener.getLogger().println("[on-monit] Detected agent info: " + info);
		listener.getLogger().println("[on-monit] Looking for node_exporter implementation...");
		for (RemoteNodeExporterProcessFactory factory : Jenkins.get().getExtensionList(RemoteNodeExporterProcessFactory.class)) {
			if (factory.isSupported(launcher, listener, info)) {
				try {
					listener.getLogger().println("[on-monit]   " + factory.getDisplayName());
					ne = factory.create(this, listener, info, tempDir(workspace), neCookie, getEffectiveNeAdditionalOptions(), debug);
					nodeExporter = ne;
					break;
				} catch (Throwable t) {
					faults.put(factory.getDisplayName(), t);
				}
			}
		}
		listener.getLogger().println("[on-monit] Looking for otel-contrib implementation...");
		for (RemoteOtelContribProcessFactory factory : Jenkins.get().getExtensionList(RemoteOtelContribProcessFactory.class)) {
			if (factory.isSupported(launcher, listener, info)) {
				try {
					listener.getLogger().println("[on-monit]   " + factory.getDisplayName());
					oc = factory.create(this, listener, info, tempDir(workspace), ocCookie, getEffectiveOcAdditionalOptions(), debug);
					otelContrib = oc;
					break;
				} catch (Throwable t) {
					faults.put(factory.getDisplayName(), t);
				}
			}
		}
		if (ne == null) {
			listener.getLogger().println("[on-monit] FATAL: Could not find a suitable node_exporter provider");
			listener.getLogger().println("[on-monit] Diagnostic report");
			for (Map.Entry<String, Throwable> fault : faults.entrySet()) {
				listener.getLogger().println("[on-monit] * " + fault.getKey());
				StringWriter sw = new StringWriter();
				fault.getValue().printStackTrace(new PrintWriter(sw));
				for (String line : StringUtils.split(sw.toString(), "\n")) {
					listener.getLogger().println("[on-monit]     " + line);
				}
			}
			throw new RuntimeException("[on-monit] Could not find a suitable node_exporter provider.");
		}
		if (oc == null) {
			listener.getLogger().println("[on-monit] FATAL: Could not find a suitable otel-contrib provider");
			listener.getLogger().println("[on-monit] Diagnostic report");
			for (Map.Entry<String, Throwable> fault : faults.entrySet()) {
				listener.getLogger().println("[on-monit] * " + fault.getKey());
				StringWriter sw = new StringWriter();
				fault.getValue().printStackTrace(new PrintWriter(sw));
				for (String line : StringUtils.split(sw.toString(), "\n")) {
					listener.getLogger().println("[on-monit]     " + line);
				}
			}
			throw new RuntimeException("[on-monit] Could not find a suitable otel-contrib provider.");
		}
		int usedPort;
		// Acquire outside the try: an interrupted acquire must not release a permit it never got.
		portSync.acquire();
		try {
			usedPort = startNodeExporter(ne, launcher, listener);
		} finally {
			portSync.release();
		}
		if (templating == null) {
			throw new NullPointerException("templating is null");
		}
		if (build == null) {
			throw new NullPointerException("build is null");
		}
		EnvVars environment = getEnvironment();
		OtlpExporterSettings otlp = OtlpExporterSettings.resolve(environment);
		printOtlpWarnings(listener, otlp);
		String config = templating.renderTemplate(templating.getJobContext(build, environment, usedPort, otlp));
		if (debug) {
			listener.getLogger().println("[on-monit] otel-collector config:\n" + config);
		}
		oc.start(listener, config);
		listener.getLogger().println(Messages.ONMonitoringStep_Started());
	}

	/**
	 * Starts node_exporter on the first free port from {@link #port}. The port is free when it is checked, but nothing
	 * reserves it, so another process may take it before node_exporter starts. In that case this searches again from
	 * the next port, up to {@value #PORT_RETRIES} times.
	 *
	 * @return the port node_exporter listens on.
	 */
	private int startNodeExporter(RemoteNodeExporterProcess ne, Launcher launcher, TaskListener listener)
			throws IOException, InterruptedException {
		int from = port;
		for (int retry = 0; ; retry++) {
			int usedPort = AvailablePortRetriever.getAvailablePort(launcher, from, port + 100).getPort();
			listener.getLogger().println("[on-monit] node_exporter will listen on " + usedPort);
			try {
				ne.start(listener, usedPort);
				return usedPort;
			} catch (PortInUseException e) {
				// Give up when the retries are used up, or when no port is left in the range to search.
				if (retry >= PORT_RETRIES || usedPort + 1 >= port + 100) {
					throw e;
				}
				listener.getLogger().println("[on-monit] port " + usedPort + " taken, retrying");
				from = usedPort + 1;
			}
		}
	}

	/**
	 * Prints the OTLP settings' warnings, and warns about otelcol options for an exporter the config no longer has.
	 * That only applies when the template picks the exporter from {@code otlpExporter} (the built-in one does); a
	 * custom template that hard-codes {@code otlp:} keeps the gRPC exporter and still needs such options.
	 */
	private void printOtlpWarnings(TaskListener listener, OtlpExporterSettings otlp) {
		for (String warning : otlp.warnings()) {
			listener.getLogger().println("[on-monit] " + warning);
		}
		String ocOptions = getEffectiveOcAdditionalOptions();
		String template = ONMonitConfig.get().getOtelConfigTemplate();
		boolean templateFollowsProtocol = template.isEmpty() || template.contains("otlpExporter");
		if (otlp.protocol() != OtlpProtocol.GRPC && templateFollowsProtocol
				&& ocOptions != null && ocOptions.contains("exporters.otlp.")) {
			listener.getLogger().println("[on-monit] WARNING: otel-contrib options set exporters.otlp.* but "
					+ "OTEL_EXPORTER_OTLP_PROTOCOL selects the otlphttp exporter; remove those options, "
					+ "otherwise the collector may fail to start");
		}
	}

	private String getEffectiveNeAdditionalOptions() {
		return StringUtils.isNotBlank(neAdditionalOptions) ? neAdditionalOptions : ONMonitConfig.get().getNeDefaultAdditionalOptions();
	}

	private String getEffectiveOcAdditionalOptions() {
		return StringUtils.isNotBlank(ocAdditionalOptions) ? ocAdditionalOptions : ONMonitConfig.get().getOcDefaultAdditionalOptions();
	}

	private void addActionOnce() throws IOException, InterruptedException {
		Run<?, ?> build = getBuild();
		ONTemplating.UrlContext urlContext = templating.getUrlContext(getEnvironment());
		build.addOrReplaceAction(new MonitoringAction(urlContext, dashboardUrl));
	}

	/**
	 * Shuts down the current remote processes. Safe to call more than once and from several threads: {@link #stop}
	 * and a failing startup may both call it. A process that was launched but not yet assigned to its field (or whose
	 * field an earlier call already cleared) is killed through its environment cookie.
	 */
	private synchronized void cleanUp() throws Exception {
		if (!launchCollector) {
			return;
		}
		TaskListener listener = getContext().get(TaskListener.class);
		if (nodeExporter != null) {
			nodeExporter.stop(listener);
			nodeExporter = null;
		} else {
			Map<String, String> neCookieEnv = new HashMap<>();
			neCookieEnv.put(ExecRemoteNodeExporterProcess.PROC_COOKIE_NAME, neCookie);
			getLauncher().kill(neCookieEnv);
		}
		if (otelContrib != null) {
			otelContrib.stop(listener);
			otelContrib = null;
		} else {
			Map<String, String> neCookieEnv = new HashMap<>();
			neCookieEnv.put(ExecRemoteOtelContribProcess.PROC_COOKIE_NAME, ocCookie);
			getLauncher().kill(neCookieEnv);
		}
		listener.getLogger().println(Messages.ONMonitoringStep_Stopped());
	}

	@Override
	public Launcher getLauncher() throws IOException, InterruptedException {
		return getContext().get(Launcher.class);
	}

	public TaskListener getListener() throws IOException, InterruptedException {
		return getContext().get(TaskListener.class);
	}

	/**
	 * The environment of this step: build-level variables, then the enclosing {@code node} block's computer
	 * variables (including agent node properties), then enclosing wrappers such as {@code withEnv} or a declarative
	 * {@code environment} block, with the innermost one winning.
	 */
	EnvVars getEnvironment() throws IOException, InterruptedException {
		EnvVars environment = getContext().get(EnvVars.class);
		return environment != null ? environment : getBuild().getEnvironment(getListener());
	}

	public Run<?, ?> getBuild() throws IOException, InterruptedException {
		return getContext().get(Run.class);
	}

	public FilePath getWorkspace() throws IOException, InterruptedException {
		return getContext().get(FilePath.class);
	}

}
