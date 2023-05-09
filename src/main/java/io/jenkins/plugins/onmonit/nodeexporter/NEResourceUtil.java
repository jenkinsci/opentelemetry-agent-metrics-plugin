package io.jenkins.plugins.onmonit.nodeexporter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class NEResourceUtil {

    public static void writeNodeExporter(OutputStream w, String os, boolean isAmd64) throws IOException {
        String resourceName;
        if ("win".equals(os)) {
            resourceName = "windows_exporter-0.22.0-amd64.exe";
        } else if ("darwin".equals(os)) {
            resourceName = "node_exporter-1.5.0.darwin-" + (isAmd64 ? "amd64" : "arm64");
        } else {
            resourceName = "node_exporter-1.5.0.linux-" + (isAmd64 ? "amd64" : "arm64");
        }
        ClassLoader cl = NEResourceUtil.class.getClassLoader();
        try (InputStream stream = cl.getResourceAsStream(resourceName)) {
            stream.transferTo(w);
        }
    }

}
