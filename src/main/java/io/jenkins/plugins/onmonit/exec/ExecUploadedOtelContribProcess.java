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

public class ExecUploadedOtelContribProcess extends ExecRemoteOtelContribProcess implements RemoteProcess {

	ExecUploadedOtelContribProcess(LauncherProvider launcherProvider, TaskListener listener, ComputerInfo info, FilePath temp, String envCookie, String additionalOptions, boolean debug, String config) throws Exception {
		super(launcherProvider, listener, info, temp, envCookie, additionalOptions, debug, config);
	}

	@Override
	protected ArgumentListBuilder getCmd() throws IOException, InterruptedException {
		FilePath executableFile = this.temp.createTempFile("otelcol-contrib", "");
		try (OutputStream w = executableFile.write()) {
			ResourceUtil.writeOtelCollector(w, info.getOs(), info.isAmd64());
			executableFile.chmod(0755);
		} catch (InterruptedException e) {
			listener.fatalError("InterruptedException while writing node exporter executable", e);
			Thread.currentThread().interrupt();
		} catch (IOException e) {
			listener.fatalError("IOException while writing node exporter executable", e);
		}
		return new ArgumentListBuilder(executableFile.getRemote());
	}

}
