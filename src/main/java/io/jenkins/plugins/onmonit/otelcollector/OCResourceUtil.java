package io.jenkins.plugins.onmonit.otelcollector;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class OCResourceUtil {

    public static void writeOtelCollector(OutputStream w, String os, boolean isAmd64) throws IOException {
        String resourceName;
        if ("win".equals(os)) {
            resourceName = "otelcol-contrib.exe";
        } else if ("darwin".equals(os)) {
            resourceName = "otelcol-contrib_0.76.1_darwin_" + (isAmd64 ? "amd64" : "arm64");
        } else {
            resourceName = "otelcol-contrib_0.76.1_linux_" + (isAmd64 ? "amd64" : "arm64");
        }
        ClassLoader cl = OCResourceUtil.class.getClassLoader();
        try (InputStream stream = cl.getResourceAsStream(resourceName)) {
            stream.transferTo(w);
        }
    }

}
