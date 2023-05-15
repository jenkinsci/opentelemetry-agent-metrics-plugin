package io.jenkins.plugins.onmonit.exec;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import io.jenkins.plugins.onmonit.LauncherProvider;
import io.jenkins.plugins.onmonit.RemoteProcess;
import io.jenkins.plugins.onmonit.ResourceUtil;
import io.jenkins.plugins.onmonit.util.ComputerInfo;

import java.io.IOException;
import java.io.OutputStream;

public class ExecUploadedNodeExporterProcess extends ExecRemoteNodeExporterProcess implements RemoteProcess {

	ExecUploadedNodeExporterProcess(LauncherProvider launcherProvider, TaskListener listener, ComputerInfo info, FilePath temp, String envCookie, String additionalOptions, boolean debug, int port) throws Exception {
		super(launcherProvider, listener, info, temp, envCookie, additionalOptions, debug, port);
	}

	@Override
	protected ArgumentListBuilder getCmd() throws IOException, InterruptedException {
		FilePath executableFile = this.temp.child("node_exporter");
		Launcher launcher = launcherProvider.getLauncher();
		try (OutputStream w = executableFile.write()) {
			ResourceUtil.writeNodeExporter(w, info.getOs(), info.isAmd64());
			executableFile.chmod(0755);
		} catch (InterruptedException e) {
			listener.fatalError("InterruptedException while writing node_exporter executable", e);
			Thread.currentThread().interrupt();
		} catch (IOException e) {
			listener.fatalError("IOException while writing node_exporter executable", e);
		}
		return new ArgumentListBuilder(executableFile.getRemote());
	}

}
