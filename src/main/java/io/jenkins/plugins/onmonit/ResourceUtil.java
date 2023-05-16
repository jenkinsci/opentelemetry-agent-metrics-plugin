package io.jenkins.plugins.onmonit;

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

    public static void writeOtelCollector(OutputStream w, String os, boolean isAmd64) throws IOException {
        String resourceName = getOtelFilename(os, isAmd64);
        ClassLoader cl = ResourceUtil.class.getClassLoader();
        try (InputStream stream = cl.getResourceAsStream("io/jenkins/plugins/onmonit/otelcollector/" + resourceName)) {
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

    public static void writeNodeExporter(OutputStream w, String os, boolean isAmd64) throws IOException {
        String resourceName = getNodeExporterFilename(os, isAmd64);
        ClassLoader cl = ResourceUtil.class.getClassLoader();
        try (InputStream stream = cl.getResourceAsStream("io/jenkins/plugins/onmonit/nodeexporter/" + resourceName)) {
            stream.transferTo(w);
        }
    }

}
