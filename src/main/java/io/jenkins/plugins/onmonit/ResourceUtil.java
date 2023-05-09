package io.jenkins.plugins.onmonit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ResourceUtil {

    public static void writeOtelCollector(OutputStream w, String os, boolean isAmd64) throws IOException {
        String resourceName;
        if ("win".equals(os)) {
            resourceName = "otelcol-contrib.exe";
        } else if ("darwin".equals(os)) {
            resourceName = "otelcol-contrib_0.76.1_darwin_" + (isAmd64 ? "amd64" : "arm64");
        } else {
            resourceName = "otelcol-contrib_0.76.1_linux_" + (isAmd64 ? "amd64" : "arm64");
        }
        ClassLoader cl = ResourceUtil.class.getClassLoader();
        try (InputStream stream = cl.getResourceAsStream("io/jenkins/plugins/onmonit/otelcollector/" + resourceName)) {
            stream.transferTo(w);
        }
    }

    public static void writeNodeExporter(OutputStream w, String os, boolean isAmd64) throws IOException {
        String resourceName;
        if ("win".equals(os)) {
            resourceName = "windows_exporter-0.22.0-amd64.exe";
        } else if ("darwin".equals(os)) {
            resourceName = "node_exporter-1.5.0.darwin-" + (isAmd64 ? "amd64" : "arm64");
        } else {
            resourceName = "node_exporter-1.5.0.linux-" + (isAmd64 ? "amd64" : "arm64");
        }
        ClassLoader cl = ResourceUtil.class.getClassLoader();
        try (InputStream stream = cl.getResourceAsStream("io/jenkins/plugins/onmonit/nodeexporter/" + resourceName)) {
            stream.transferTo(w);
        }
    }

}
