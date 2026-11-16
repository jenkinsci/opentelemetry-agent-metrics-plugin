package io.jenkins.plugins.onmonit.exec;

import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import io.jenkins.plugins.onmonit.LauncherProvider;
import io.jenkins.plugins.onmonit.ResourceUtil;
import io.jenkins.plugins.onmonit.util.BinaryCache;
import io.jenkins.plugins.onmonit.util.ComputerInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

public class ExecUploadedOtelContribProcess extends ExecRemoteOtelContribProcess {

	ExecUploadedOtelContribProcess(LauncherProvider launcherProvider, TaskListener listener, ComputerInfo info, FilePath temp, String envCookie, String additionalOptions, boolean debug) throws Exception {
		super(launcherProvider, listener, info, temp, envCookie, additionalOptions, debug);
	}

	@Override
	protected ArgumentListBuilder getCmd() throws IOException, InterruptedException {
		try {
			Path source = ResourceUtil.getOtelCollectorPath(info.getOs(), info.isAmd64());
			FilePath executableFile = BinaryCache.obtain(temp, listener, "otelcol-contrib", BinaryCache.uploadKey(source),
					ResourceUtil.getOtelFilename(info.getOs(), info.isAmd64()), this::createTempExecutableFile, target -> {
						try (OutputStream w = target.write()) {
							ResourceUtil.writeOtelCollector(w, info.getOs(), info.isAmd64());
						}
					});
			return new ArgumentListBuilder(executableFile.getRemote());
		} catch (InterruptedException e) {
			listener.fatalError("InterruptedException while writing otelcol-contrib executable %s", e);
			Thread.currentThread().interrupt();
		} catch (IOException e) {
			listener.fatalError("IOException while writing otelcol-contrib executable %s", e);
		}
		throw new RuntimeException("could not start otelcol-contrib");
	}

}
