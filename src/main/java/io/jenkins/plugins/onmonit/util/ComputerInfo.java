package io.jenkins.plugins.onmonit.util;

import java.io.Serializable;

public class ComputerInfo implements Serializable {

	private static final long serialVersionUID = 1L;

	private String os;
	private boolean isAmd64;

	ComputerInfo(String os, boolean isAmd64) {
		this.os = os;
		this.isAmd64 = isAmd64;
	}

	public String getOs() {
		return os;
	}

	public boolean isAmd64() {
		return isAmd64;
	}

	@Override
	public String toString() {
		return "ComputerInfo{" +
				"os='" + os + '\'' +
				", isAmd64=" + isAmd64 +
				'}';
	}
}
