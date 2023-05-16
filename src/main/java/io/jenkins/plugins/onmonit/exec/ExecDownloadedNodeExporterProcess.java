package io.jenkins.plugins.onmonit.exec;

import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import io.jenkins.plugins.onmonit.LauncherProvider;
import io.jenkins.plugins.onmonit.ONMonitConfig;
import io.jenkins.plugins.onmonit.RemoteProcess;
import io.jenkins.plugins.onmonit.ResourceUtil;
import io.jenkins.plugins.onmonit.util.ComputerInfo;
import io.jenkins.plugins.onmonit.util.DownloadOnSlaveCallable;

import java.io.IOException;

public class ExecDownloadedNodeExporterProcess extends ExecRemoteNodeExporterProcess implements RemoteProcess {

	ExecDownloadedNodeExporterProcess(LauncherProvider launcherProvider, TaskListener listener, ComputerInfo info, FilePath temp, String envCookie, String additionalOptions, boolean debug, int port) throws Exception {
		super(launcherProvider, listener, info, temp, envCookie, additionalOptions, debug, port);
	}

	@Override
	protected ArgumentListBuilder getCmd() throws IOException, InterruptedException {
		FilePath executableFile = this.temp.createTempFile("node_exporter", "");
		String url = ONMonitConfig.get().getDownloadBaseUrl() + "/" + ResourceUtil.getNodeExporterFilename(info.getOs(), info.isAmd64());
		try {
			launcherProvider.getLauncher().getChannel().call(new DownloadOnSlaveCallable(url, executableFile.getRemote()));
			executableFile.chmod(0755);
		} catch (InterruptedException e) {
			listener.fatalError("InterruptedException while writing node_exporter executable", e);
			Thread.currentThread().interrupt();
		} catch (Throwable e) {
			listener.fatalError("IOException while writing node_exporter executable", e);
		}
		return new ArgumentListBuilder(executableFile.getRemote());
	}

}
