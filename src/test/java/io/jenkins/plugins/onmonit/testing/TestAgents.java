package io.jenkins.plugins.onmonit.testing;

import static org.assertj.core.api.Assertions.assertThat;

import hudson.EnvVars;
import hudson.slaves.DumbSlave;
import io.jenkins.plugins.onmonit.util.RemoteComputerInfoRetriever;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jvnet.hudson.test.JenkinsRule;

/** Builds test agents whose PATH contains only a given directory. */
public final class TestAgents {

    private TestAgents() {}

    /**
     * Creates an online agent with {@code label}. Its PATH is {@code pathDir} only (plus System32 on Windows), so
     * binaries installed on the developer's machine are not picked up. Stand-ins write markers to {@code markerDir}.
     */
    public static DumbSlave agent(JenkinsRule j, String label, Path pathDir, Path markerDir) throws Exception {
        return agent(j, label, pathDir, markerDir, 1);
    }

    /** Like {@link #agent(JenkinsRule, String, Path, Path)} with {@code numExecutors} executors. */
    public static DumbSlave agent(JenkinsRule j, String label, Path pathDir, Path markerDir, int numExecutors)
            throws Exception {
        String path = pathDir.toAbsolutePath().toString();
        if (RemoteComputerInfoRetriever.isWindows()) {
            path += java.io.File.pathSeparator + System.getenv("SystemRoot") + "\\System32";
        }
        EnvVars env = new EnvVars();
        env.put("PATH", path);
        env.put("ONMONIT_STANDIN_DIR", markerDir.toAbsolutePath().toString());
        DumbSlave agent = new DumbSlave(
                "agent" + j.jenkins.getNodes().size(),
                j.createTmpDir().getPath(),
                j.createComputerLauncher(env));
        agent.setNumExecutors(numExecutors);
        agent.setLabelString(label);
        j.jenkins.addNode(agent);
        j.waitOnline(agent);
        return agent;
    }

    /** Creates an empty directory under {@code base} to use as an agent PATH. */
    public static Path emptyDir(Path base) throws IOException {
        return Files.createDirectories(base.resolve("empty-path"));
    }

    /** The {@code JENKINS_HOME} environment variable (set by Surefire) as a path. */
    public static Path jenkinsHomeEnv() {
        String home = System.getenv("JENKINS_HOME");
        assertThat(home).as("JENKINS_HOME environment variable").isNotNull();
        return Path.of(home);
    }
}
