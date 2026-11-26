package io.jenkins.plugins.onmonit;

import hudson.Launcher;
import hudson.model.TaskListener;

import java.io.IOException;

public interface RemoteProcess {

	/**
	 * Stops the process.
	 *
	 * @param listener for logging.
	 */
	void stop(TaskListener listener) throws IOException, InterruptedException;

	/**
	 * Stops the process with the given launcher rather than the one the process was created with. Used when that one
	 * can no longer be obtained, for example after the enclosing {@code node} block has ended. Implementations that
	 * do not override this fall back to {@link #stop(TaskListener)}.
	 *
	 * @param listener for logging.
	 * @param launcher to kill the process with.
	 */
	default void stop(TaskListener listener, Launcher launcher) throws IOException, InterruptedException {
		stop(listener);
	}

}
