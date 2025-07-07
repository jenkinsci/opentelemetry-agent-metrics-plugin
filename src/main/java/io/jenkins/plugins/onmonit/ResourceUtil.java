package io.jenkins.plugins.onmonit;

import hudson.FilePath;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ResourceUtil {

    public static String getOtelFilename(String os, boolean isAmd64) {
        if ("win".equals(os)) {
            return "otelcol-contrib_0.70.0_win_amd64.exe";
        } else if ("darwin".equals(os)) {
            return "otelcol-contrib_0.70.0_darwin_" + (isAmd64 ? "amd64" : "arm64");
        } else {
            return "otelcol-contrib_0.70.0_linux_" + (isAmd64 ? "amd64" : "arm64");
        }
    }

    private static FilePath getResourceRoot() throws IOException, InterruptedException {
        String home = System.getenv().get("JENKINS_HOME");
        if (home == null)  {
            throw new IOException("JENKINS_HOME not defined");
        }
        FilePath container = new FilePath(new File(home));
        if (!container.isDirectory()) {
            throw new IOException("JENKINS_HOME is not a directory");
        }
        container = container.child("opentelemetry-agent-metrics");
        if (!container.isDirectory() || !container.exists()) {
            throw new IOException("Plugin executables source \"" + container.getRemote() + "\" is not a directory");
        }
        return container;
    }

    public static void writeOtelCollector(OutputStream w, String os, boolean isAmd64) throws IOException, InterruptedException {
        String resourceName = getOtelFilename(os, isAmd64);
        FilePath executable = getResourceRoot().child(resourceName);
        try (InputStream stream = executable.read()) {
            stream.transferTo(w);
        }
    }

    public static String getNodeExporterFilename(String os, boolean isAmd64) {
        if ("win".equals(os)) {
            return "windows_exporter-0.22.0-amd64.exe";
        } else if ("darwin".equals(os)) {
            return "node_exporter-1.5.0.darwin-" + (isAmd64 ? "amd64" : "arm64");
        } else {
            return "node_exporter-1.5.0.linux-" + (isAmd64 ? "amd64" : "arm64");
        }
    }

    public static void writeNodeExporter(OutputStream w, String os, boolean isAmd64) throws IOException, InterruptedException {
        String resourceName = getNodeExporterFilename(os, isAmd64);
        FilePath executable = getResourceRoot().child(resourceName);
        try (InputStream stream = executable.read()) {
            stream.transferTo(w);
        }
    }

}
