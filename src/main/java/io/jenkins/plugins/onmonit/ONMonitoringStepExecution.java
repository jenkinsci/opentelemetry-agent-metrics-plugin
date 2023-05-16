package io.jenkins.plugins.onmonit;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.TaskListener;
import io.jenkins.plugins.onmonit.util.ComputerInfo;
import io.jenkins.plugins.onmonit.util.RemoteComputerInfoRetriever;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.slaves.WorkspaceList;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class ONMonitoringStepExecution extends StepExecution implements LauncherProvider {

	private static final long serialVersionUID = 1L;

	private int port;

	private boolean debug;

	private String neAdditionalOptions;

	private String ocAdditionalOptions;

	private transient ONTemplating templating = new ONTemplating();

	/**
	 * The proxy for the real remote node_exporter process that is on the other side of the channel (as the process needs to
	 * run on a remote machine)
	 */
	private transient RemoteProcess nodeExporter = null;

	/**
	 * The proxy for the real remote otel-contrib process that is on the other side of the channel (as the process needs to
	 * run on a remote machine)
	 */
	private transient RemoteProcess otelContrib = null;

	ONMonitoringStepExecution(StepContext context, int port, boolean debug, String neAdditionalOptions, String ocAdditionalOptions) {
		super(context);
		this.port = port;
		this.debug = debug;
		this.neAdditionalOptions = neAdditionalOptions;
		this.ocAdditionalOptions = ocAdditionalOptions;
	}

	@Override
	public boolean start() throws Exception {
		StepContext context = getContext();
		try {
			initRemoteProcesses();
		} catch (Exception e) {
			cleanUp();
			throw e;
		}
		context.newBodyInvoker().
				withCallback(new Callback(this)).start();
		return false;
	}

	@Override
	public void stop(@NonNull Throwable cause) throws Exception {
		try {
			cleanUp();
		} finally {
			super.stop(cause);
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		try {
			cleanUp();
			initRemoteProcesses();
		} catch (InterruptedException e) {
			getContext().onFailure(e);
			Thread.currentThread().interrupt();
		} catch (Exception x) {
			getContext().onFailure(x);
			try {
				x.printStackTrace(getListener().getLogger());
				getListener().getLogger().println(Messages.ONMonitoring_CouldNotStartProcesses());
			} catch (IOException e) {
				// suppressed
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private static class Callback extends BodyExecutionCallback.TailCall {

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

	static FilePath tempDir(FilePath ws) {
		return WorkspaceList.tempDir(ws);
	}

	/**
	 * Initializes the node_exporter and otel-contrib processes.
	 *
	 * @throws IOException
	 */
	private void initRemoteProcesses() throws IOException, InterruptedException {

		// TODO UI could be streamlined by unifying implementation (trying remote and fallback to upload)
		TaskListener listener = getListener();
		Run<?, ?> build = getBuild();
		FilePath workspace = getWorkspace();
		listener.getLogger().println("[on-monit] Looking for node_exporter implementation...");
		ComputerInfo info = RemoteComputerInfoRetriever.getRemoteInfo(getLauncher());
		Map<String, Throwable> faults = new LinkedHashMap<>();
		for (RemoteNodeExporterProcessFactory factory : Jenkins.get().getExtensionList(RemoteNodeExporterProcessFactory.class)) {
			if (factory.isSupported(getLauncher(), listener, info)) {
				try {
					listener.getLogger().println("[on-monit]   " + factory.getDisplayName());
					nodeExporter = factory.start(this, listener, info, tempDir(workspace), UUID.randomUUID().toString(), neAdditionalOptions, debug, port);
					break;
				} catch (Throwable t) {
					faults.put(factory.getDisplayName(), t);
				}
			}
		}
		listener.getLogger().println("[on-monit] Looking for otel-contrib implementation...");
		String config = templating.renderTemplate(templating.getJobContext(build, build.getEnvironment(listener), port));
		for (RemoteOtelContribProcessFactory factory : Jenkins.get().getExtensionList(RemoteOtelContribProcessFactory.class)) {
			if (factory.isSupported(getLauncher(), listener, info)) {
				try {
					listener.getLogger().println("[on-monit]   " + factory.getDisplayName());
					otelContrib = factory.start(this, listener, info, tempDir(workspace), UUID.randomUUID().toString(), ocAdditionalOptions, debug, config);
					break;
				} catch (Throwable t) {
					faults.put(factory.getDisplayName(), t);
				}
			}
		}
		if (nodeExporter == null) {
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
		if (otelContrib == null) {
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

		listener.getLogger().println(Messages.ONMonitoringBuildWrapper_Started());
	}

	/**
	 * Shuts down the current remote processes.
	 */
	private void cleanUp() throws Exception {
		TaskListener listener = getContext().get(TaskListener.class);
		if (nodeExporter != null) {
			nodeExporter.stop(listener);
			nodeExporter = null;
			listener.getLogger().println(Messages.ONMonitoringBuildWrapper_Stopped());
		}
		if (otelContrib != null) {
			otelContrib.stop(listener);
			otelContrib = null;
			listener.getLogger().println(Messages.ONMonitoringBuildWrapper_Stopped());
		}
	}

	@Override
	public Launcher getLauncher() throws IOException, InterruptedException {
		return getContext().get(Launcher.class);
	}

	public TaskListener getListener() throws IOException, InterruptedException {
		return getContext().get(TaskListener.class);
	}

	public Run<?, ?> getBuild() throws IOException, InterruptedException {
		return getContext().get(Run.class);
	}

	public FilePath getWorkspace() throws IOException, InterruptedException {
		return getContext().get(FilePath.class);
	}

}
