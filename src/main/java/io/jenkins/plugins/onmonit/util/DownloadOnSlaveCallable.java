package io.jenkins.plugins.onmonit.util;

import jenkins.security.MasterToSlaveCallable;

import java.io.BufferedInputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class DownloadOnSlaveCallable extends MasterToSlaveCallable<Void, Throwable> {

	private static final long serialVersionUID = 1031664907899192911L;

	private String url;

	private String localPath;

	public DownloadOnSlaveCallable(String url, String localPath) {
		this.url = url;
		this.localPath = localPath;
	}

	@Override
	public Void call() throws Throwable {
		try (BufferedInputStream in = new BufferedInputStream(new URL(url).openStream())) {
			Files.copy(in, Paths.get(localPath), StandardCopyOption.REPLACE_EXISTING);
		}
		return null;
	}

}