package io.jenkins.plugins.onmonit.testing;

import hudson.slaves.EnvironmentVariablesNodeProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jvnet.hudson.test.JenkinsRule;

/** Jenkins-side set-up and build-log helpers shared by the step tests. */
public final class JenkinsFixtures {

    private static final Pattern LISTEN = Pattern.compile("node_exporter will listen on (\\d+)");

    private JenkinsFixtures() {}

    /** Sets a global environment variable, visible to every build and every step. */
    public static void globalEnv(JenkinsRule j, String key, String value) throws Exception {
        EnvironmentVariablesNodeProperty prop =
                j.jenkins.getGlobalNodeProperties().get(EnvironmentVariablesNodeProperty.class);
        if (prop == null) {
            prop = new EnvironmentVariablesNodeProperty();
            j.jenkins.getGlobalNodeProperties().add(prop);
        }
        prop.getEnvVars().put(key, value);
        j.jenkins.save();
    }

    /** The node_exporter ports logged by {@code onMonit} ({@code node_exporter will listen on <port>}), in order. */
    public static List<Integer> ports(String log) {
        List<Integer> ports = new ArrayList<>();
        Matcher m = LISTEN.matcher(log);
        while (m.find()) {
            ports.add(Integer.parseInt(m.group(1)));
        }
        return ports;
    }
}
