package io.jenkins.plugins.onmonit.util;

import hudson.remoting.Callable;
import hudson.Launcher;
import hudson.remoting.VirtualChannel;
import jenkins.security.MasterToSlaveCallable;
import org.jenkinsci.remoting.RoleChecker;

public class RemoteComputerInfoRetriever {
	public static ComputerInfo getRemoteInfo(Launcher launcher) {
		try {
			VirtualChannel ch = launcher.getChannel();
			if (ch == null) {
				throw new IllegalArgumentException("Channel is null");
			}
			return ch.call(new MasterToSlaveCallable<ComputerInfo, Throwable>() {
				private static final long serialVersionUID = 5982559307031083756L;

				@Override
				public ComputerInfo call() throws Throwable {
					return new ComputerInfo(getOs(), isAmd64());
				}
			});
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	public static String getOs() {
		if (isWindows()) {
			return "win";
		} else if (isDarwin()) {
			return "darwin";
		}
		return "linux";
	}

	public static boolean isWindows() {
		return System.getProperty("os.name").toLowerCase().contains("win");
	}

	public static boolean isDarwin() {
		String os = System.getProperty("os.name").toLowerCase();
		return os.contains("mac") || os.contains("darwin");
	}

	public static boolean isAmd64() {
		String arch = System.getProperty("os.arch").toLowerCase();
		return arch.contains("amd64") || arch.contains("x64") || arch.contains("x86-64");
	}
}
