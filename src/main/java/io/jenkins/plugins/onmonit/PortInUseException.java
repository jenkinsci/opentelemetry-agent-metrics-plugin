package io.jenkins.plugins.onmonit;

import hudson.AbortException;

import java.io.Serial;

/**
 * Thrown when node_exporter cannot listen on its port because another process took it after the port was checked.
 */
public class PortInUseException extends AbortException {

	@Serial
	private static final long serialVersionUID = 1L;

	private final int port;

	public PortInUseException(int port) {
		super("Failed to start node_exporter, port " + port + " is already in use");
		this.port = port;
	}

	public int getPort() {
		return port;
	}

}
