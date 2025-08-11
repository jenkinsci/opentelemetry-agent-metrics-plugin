package io.jenkins.plugins.onmonit.util;

import java.io.Serial;
import java.io.Serializable;

public class AvailablePort implements Serializable {

	@Serial
    private static final long serialVersionUID = 1L;

	private int port;

	AvailablePort(int port) {
		this.port = port;
	}

	public int getPort() {
		return port;
	}

}
