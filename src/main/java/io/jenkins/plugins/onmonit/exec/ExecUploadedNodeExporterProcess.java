package io.jenkins.plugins.onmonit.exec;

import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import io.jenkins.plugins.onmonit.LauncherProvider;
import io.jenkins.plugins.onmonit.RemoteProcess;
import io.jenkins.plugins.onmonit.ResourceUtil;
import io.jenkins.plugins.onmonit.util.ComputerInfo;

import java.io.IOException;
import java.io.OutputStream;

public class ExecUploadedNodeExporterProcess extends ExecRemoteNodeExporterProcess {

	ExecUploadedNodeExporterProcess(LauncherProvider launcherProvider, TaskListener listener, ComputerInfo info, FilePath temp, String envCookie, String additionalOptions, boolean debug) throws Exception {
		super(launcherProvider, listener, info, temp, envCookie, additionalOptions, debug);
	}

	@Override
	protected ArgumentListBuilder getCmd() throws IOException, InterruptedException {
		FilePath executableFile = this.createTempExecutableFile();
		try (OutputStream w = executableFile.write()) {
			ResourceUtil.writeNodeExporter(w, info.getOs(), info.isAmd64());
			executableFile.chmod(0755);
			return new ArgumentListBuilder(executableFile.getRemote());
		} catch (InterruptedException e) {
			listener.fatalError("InterruptedException while writing node_exporter executable %s", e);
			Thread.currentThread().interrupt();
		} catch (IOException e) {
			listener.fatalError("IOException while writing node_exporter executable %s", e);
		}
		throw new RuntimeException("could not start node_exporter");
	}

}
