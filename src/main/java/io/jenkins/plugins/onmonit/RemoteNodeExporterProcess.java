package io.jenkins.plugins.onmonit;

import hudson.model.TaskListener;

import java.io.IOException;

public interface RemoteNodeExporterProcess extends RemoteProcess {

	/**
	 * Starts the process.
	 *
	 * @param listener for logging.
	 * @param port the port on which the node_exporter process should listen on
	 */
	void start(TaskListener listener, int port) throws IOException, InterruptedException;

}
