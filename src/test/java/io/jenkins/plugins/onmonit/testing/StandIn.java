package io.jenkins.plugins.onmonit.testing;

import io.jenkins.plugins.onmonit.ResourceUtil;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Stand-in for {@code otelcol-contrib}, {@code node_exporter} and {@code windows_exporter}.
 * Started by the launchers that {@link StandIns} writes; {@code args[0]} is the {@link Role} name,
 * the remaining arguments are the ones the plugin passed to the binary.
 */
public final class StandIn {

    /** Environment variable naming the directory that receives marker files. */
    public static final String MARKER_DIR_ENV = "ONMONIT_STANDIN_DIR";

    /**
     * Command-line argument prefix naming a gate file: an otelcol stand-in given {@code <prefix><file>} (for example
     * through the step's {@code ocAdditionalOptions}) writes its marker, then waits until the file exists before
     * printing its ready line. It is an argument rather than an environment variable because the plugin launches
     * the binaries with the agent's environment, so a {@code withEnv} in one {@code parallel} branch cannot reach
     * them, while {@code ocAdditionalOptions} is per {@code onMonit} step.
     */
    public static final String GATE_FILE_ARG = "--onmonit.standin.gate-file=";

    /**
     * Command-line argument prefix naming the marker directory, for agents whose environment the test cannot set
     * (such as an inbound agent, which inherits the test JVM's environment). It is passed through the step's
     * {@code neAdditionalOptions} and {@code ocAdditionalOptions} for the same reason as {@link #GATE_FILE_ARG}, and
     * takes precedence over {@link #MARKER_DIR_ENV}.
     */
    public static final String MARKER_DIR_ARG = "--onmonit.standin.marker-dir=";

    /**
     * Command-line argument prefix naming a fail file: a stand-in given {@code <prefix><file>} exits with status 1,
     * without writing a marker, if the file exists when it starts. A test creates the file to make later starts fail,
     * for example the restart of the processes after a controller restart.
     */
    public static final String FAIL_FILE_ARG = "--onmonit.standin.fail-file=";

    /** System property set by the Windows launcher, which is a separate parent process of this JVM. */
    static final String WRAPPED_PROPERTY = "onmonit.standin.wrapped";

    public enum Role {
        OTELCOL("otelcol-contrib", ResourceUtil.getOtelFilename("linux", true), -1),
        NODE_EXPORTER("node_exporter", ResourceUtil.getNodeExporterFilename("linux", true), 9100),
        WINDOWS_EXPORTER("windows_exporter", ResourceUtil.getNodeExporterFilename("win", true), 9182);

        private final String binaryName;
        private final String version;
        private final int defaultPort;

        Role(String binaryName, String resourceFilename, int defaultPort) {
            this.binaryName = binaryName;
            this.version = Versions.of(resourceFilename);
            this.defaultPort = defaultPort;
        }

        public String binaryName() {
            return binaryName;
        }

        public String version() {
            return version;
        }

        public boolean isExporter() {
            return this != OTELCOL;
        }
    }

    private static final String CONFIG_PREFIX = "--config=file:";
    private static final String LISTEN_PREFIX = "--web.listen-address=";

    private StandIn() {}

    public static void main(String[] argv) throws Exception {
        Role role = Role.valueOf(argv[0]);
        List<String> args = Arrays.asList(argv).subList(1, argv.length);
        PrintStream out = System.out;

        if (args.contains("--version")) {
            out.println(role.binaryName() + " version " + role.version());
            out.flush();
            System.exit(0);
            return;
        }

        Path fail = lastPath(args, FAIL_FILE_ARG);
        if (fail != null && Files.exists(fail)) {
            System.err.println(role.binaryName() + " stand-in failing: " + fail + " exists");
            System.err.flush();
            System.exit(1);
            return;
        }

        ProcessHandle self = ProcessHandle.current();
        long pid = self.pid();
        ProcessHandle parent =
                Boolean.getBoolean(WRAPPED_PROPERTY) ? self.parent().orElse(null) : null;
        long parentPid = parent == null ? -1L : parent.pid();
        Path markerDir = lastPath(args, MARKER_DIR_ARG);
        if (markerDir == null) {
            String markerEnv = System.getenv(MARKER_DIR_ENV);
            markerDir = markerEnv == null || markerEnv.isEmpty() ? null : Path.of(markerEnv);
        }
        String baseName = role.name() + "-" + pid;

        Properties marker = new Properties();
        marker.setProperty("role", role.name());
        marker.setProperty("pid", Long.toString(pid));
        marker.setProperty("parentPid", Long.toString(parentPid));
        // Start instants let StandIns.killAll tell a recorded process from a later one that reused its PID.
        self.info().startInstant().ifPresent(i -> marker.setProperty("startInstant", i.toString()));
        if (parent != null) {
            parent.info().startInstant().ifPresent(i -> marker.setProperty("parentStartInstant", i.toString()));
        }
        marker.setProperty("args", String.join("\n", args));

        String readyLine;
        if (role.isExporter()) {
            int port = listenPort(args, role.defaultPort);
            // Wildcard bind on purpose: the plugin's AvailablePortRetriever probes with a wildcard bind and the real
            // exporters listen on ":port", so a loopback-only stand-in could hide port clashes.
            ServerSocket server = new ServerSocket(port);
            Thread acceptor = new Thread(() -> serve(server), "stand-in-http");
            acceptor.setDaemon(true);
            acceptor.start();
            marker.setProperty("port", Integer.toString(port));
            readyLine = "Listening on :" + port;
        } else {
            String config = args.stream()
                    .filter(a -> a.startsWith(CONFIG_PREFIX))
                    .map(a -> a.substring(CONFIG_PREFIX.length()))
                    .reduce((a, b) -> b)
                    .orElse(null);
            if (config != null && markerDir != null) {
                Path copy = markerDir.resolve(baseName + ".yaml");
                Files.copy(Path.of(config), copy, StandardCopyOption.REPLACE_EXISTING);
                marker.setProperty("configCopy", copy.toAbsolutePath().toString());
            }
            readyLine = "Everything is ready.";
        }

        if (markerDir != null) {
            Files.createDirectories(markerDir);
            Path tmp = markerDir.resolve(baseName + ".tmp");
            try (OutputStream os = Files.newOutputStream(tmp)) {
                marker.store(os, "onmonit stand-in");
            }
            Files.move(
                    tmp,
                    markerDir.resolve(baseName + ".properties"),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        }

        Path gate = lastPath(args, GATE_FILE_ARG);
        if (gate != null && !role.isExporter()) {
            while (!Files.exists(gate)) {
                Thread.sleep(100);
            }
        }

        out.println(readyLine);
        out.flush();
        while (true) {
            Thread.sleep(Long.MAX_VALUE);
        }
    }

    /** The path given by the last argument starting with {@code prefix}, or {@code null} if there is none. */
    private static Path lastPath(List<String> args, String prefix) {
        return args.stream()
                .filter(a -> a.startsWith(prefix))
                .map(a -> Path.of(a.substring(prefix.length())))
                .reduce((a, b) -> b)
                .orElse(null);
    }

    private static int listenPort(List<String> args, int defaultPort) {
        return args.stream()
                .filter(a -> a.startsWith(LISTEN_PREFIX))
                .map(a -> a.substring(a.lastIndexOf(':') + 1))
                .map(Integer::parseInt)
                .reduce((a, b) -> b)
                .orElse(defaultPort);
    }

    private static void serve(ServerSocket server) {
        while (true) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> answer(s), "stand-in-http-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    /** Reads the request head and answers {@code 200 OK} with an empty body. */
    private static void answer(Socket s) {
        try (s) {
            s.setSoTimeout(10_000);
            InputStream in = s.getInputStream();
            int matched = 0;
            byte[] end = {'\r', '\n', '\r', '\n'};
            int b;
            while (matched < end.length && (b = in.read()) >= 0) {
                matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
            }
            String response = "HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\n"
                    + "Content-Length: 0\r\nConnection: close\r\n\r\n";
            s.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
        } catch (IOException ignored) {
            // client went away
        }
    }
}
