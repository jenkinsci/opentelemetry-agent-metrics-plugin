package io.jenkins.plugins.onmonit.exec;

import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.util.ArgumentListBuilder;
import io.jenkins.plugins.onmonit.LauncherProvider;
import io.jenkins.plugins.onmonit.ONMonitConfig;
import io.jenkins.plugins.onmonit.RemoteProcess;
import io.jenkins.plugins.onmonit.ResourceUtil;
import io.jenkins.plugins.onmonit.util.ComputerInfo;
import io.jenkins.plugins.onmonit.util.DownloadOnSlaveCallable;

import java.io.IOException;

public class ExecDownloadedNodeExporterProcess extends ExecRemoteNodeExporterProcess {

	ExecDownloadedNodeExporterProcess(LauncherProvider launcherProvider, TaskListener listener, ComputerInfo info, FilePath temp, String envCookie, String additionalOptions, boolean debug) throws Exception {
		super(launcherProvider, listener, info, temp, envCookie, additionalOptions, debug);
	}

	@Override
	protected ArgumentListBuilder getCmd() throws IOException, InterruptedException {
		FilePath executableFile = this.createTempExecutableFile();
		String url = ONMonitConfig.get().getDownloadBaseUrl() + "/" + ResourceUtil.getNodeExporterFilename(info.getOs(), info.isAmd64());
		try {
			VirtualChannel channel = launcherProvider.getLauncher().getChannel();
			if (channel == null) {
				throw new RuntimeException("Could not access channel");
			}
			channel.call(new DownloadOnSlaveCallable(url, executableFile.getRemote()));
			executableFile.chmod(0755);
			return new ArgumentListBuilder(executableFile.getRemote());
		} catch (InterruptedException e) {
			listener.fatalError("InterruptedException while writing node_exporter executable %s", e);
			Thread.currentThread().interrupt();
		} catch (Throwable e) {
			listener.fatalError("IOException while writing node_exporter executable %s", e);
		}
		throw new RuntimeException("could not start node_exporter");
	}

}
