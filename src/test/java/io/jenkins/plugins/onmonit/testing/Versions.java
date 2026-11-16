package io.jenkins.plugins.onmonit.testing;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the version out of the plugin's binary resource file names, e.g. {@code node_exporter-1.9.1.linux-amd64}. */
public final class Versions {

    private static final Pattern VERSION = Pattern.compile("(\\d+\\.\\d+\\.\\d+)");

    private Versions() {}

    /** The first {@code major.minor.patch} in {@code resourceFilename}. */
    public static String of(String resourceFilename) {
        Matcher m = VERSION.matcher(resourceFilename);
        if (!m.find()) {
            throw new IllegalStateException("No version in " + resourceFilename);
        }
        return m.group(1);
    }
}
