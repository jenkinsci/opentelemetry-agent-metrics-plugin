package io.jenkins.plugins.onmonit;

import hudson.model.TaskListener;

import java.io.IOException;

public interface RemoteOtelContribProcess extends RemoteProcess {

	/**
	 * Starts the process.
	 *
	 * @param listener for logging.
	 * @param config the config for the otel-contrib process.
	 */
	void start(TaskListener listener, String config) throws IOException, InterruptedException;

}
