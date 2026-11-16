package io.jenkins.plugins.onmonit.exec;

import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.util.ArgumentListBuilder;
import io.jenkins.plugins.onmonit.LauncherProvider;
import io.jenkins.plugins.onmonit.ONMonitConfig;
import io.jenkins.plugins.onmonit.ResourceUtil;
import io.jenkins.plugins.onmonit.util.BinaryCache;
import io.jenkins.plugins.onmonit.util.ComputerInfo;

import java.io.IOException;

public class ExecDownloadedOtelContribProcess extends ExecRemoteOtelContribProcess {

	ExecDownloadedOtelContribProcess(LauncherProvider launcherProvider, TaskListener listener, ComputerInfo info, FilePath temp, String envCookie, String additionalOptions, boolean debug) throws Exception {
		super(launcherProvider, listener, info, temp, envCookie, additionalOptions, debug);
	}

	@Override
	protected ArgumentListBuilder getCmd() throws IOException, InterruptedException {
		String filename = ResourceUtil.getOtelFilename(info.getOs(), info.isAmd64());
		String url = ONMonitConfig.get().getDownloadBaseUrl() + "/" + filename;
		try {
			VirtualChannel channel = launcherProvider.getLauncher().getChannel();
			if (channel == null) {
				throw new RuntimeException("Could not access channel");
			}
			FilePath executableFile = BinaryCache.obtain(temp, listener, "otelcol-contrib", BinaryCache.sha256Hex(url),
					filename, this::createTempExecutableFile, BinaryCache.download(channel, url));
			return new ArgumentListBuilder(executableFile.getRemote());
		} catch (InterruptedException e) {
			listener.fatalError("InterruptedException while writing otelcol-contrib executable %s", e);
			Thread.currentThread().interrupt();
		} catch (Throwable e) {
			listener.fatalError("IOException while writing otelcol-contrib executable %s", e);
		}
		throw new RuntimeException("could not start otelcol-contrib");
	}

}
