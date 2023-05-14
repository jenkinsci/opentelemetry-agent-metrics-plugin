package io.jenkins.plugins.onmonit;

import hudson.model.TaskListener;

import java.io.IOException;

public interface RemoteProcess {

	/**
	 * Stops the process.
	 *
	 * @param listener for logging.
	 */
	void stop(TaskListener listener) throws IOException, InterruptedException;

}
