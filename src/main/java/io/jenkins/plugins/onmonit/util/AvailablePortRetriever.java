package io.jenkins.plugins.onmonit.util;

import hudson.Launcher;
import hudson.remoting.VirtualChannel;
import jenkins.security.MasterToSlaveCallable;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public class AvailablePortRetriever {

	private static final Map<VirtualChannel, Semaphore> syncPortRetrieval = new ConcurrentHashMap<>();

	public static Semaphore getSyncOjbectForLauncher(Launcher launcher) {
		return syncPortRetrieval.computeIfAbsent(launcher.getChannel(), c -> new Semaphore(1));
	}

	public static AvailablePort getAvailablePort(Launcher launcher, int basePort, int maxPort) {
		try {
			VirtualChannel ch = launcher.getChannel();
			if (ch == null) {
				throw new IllegalArgumentException("Channel is null");
			}
			Semaphore syncObj = syncPortRetrieval.computeIfAbsent(ch, c -> new Semaphore(1));
			if (syncObj.availablePermits() != 0) {
				throw new IllegalArgumentException("Caller is supposed to sync");
			}
			return ch.call(new AvailablePortCallable(basePort, maxPort));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	public static class AvailablePortCallable extends MasterToSlaveCallable<AvailablePort, Throwable> {

		private static final long serialVersionUID = -4472949825725190221L;

		private int basePort;
		private int maxPort;

		AvailablePortCallable(int basePort, int maxPort) {
			this.basePort = basePort;
			this.maxPort = maxPort;
		}

		@Override
		public AvailablePort call() throws Throwable {
			for (int tryPort = basePort; tryPort < maxPort; tryPort++) {
				try (ServerSocket serverSocket = new ServerSocket(tryPort)) {
					return new AvailablePort(tryPort);
				} catch (IOException e) {
					// could not bind to this port
				}
			}
			throw new RuntimeException("Could not find open port in range " + basePort + "-" + maxPort);
		}
	}

}
