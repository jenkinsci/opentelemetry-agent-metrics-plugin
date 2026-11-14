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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stand-in for {@code otelcol-contrib}, {@code node_exporter} and {@code windows_exporter}.
 * Started by the launchers that {@link StandIns} writes; {@code args[0]} is the {@link Role} name,
 * the remaining arguments are the ones the plugin passed to the binary.
 */
public final class StandIn {

    /** Environment variable naming the directory that receives marker files. */
    public static final String MARKER_DIR_ENV = "ONMONIT_STANDIN_DIR";

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
            Matcher m = Pattern.compile("(\\d+\\.\\d+\\.\\d+)").matcher(resourceFilename);
            if (!m.find()) {
                throw new IllegalStateException("No version in " + resourceFilename);
            }
            this.version = m.group(1);
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

        long pid = ProcessHandle.current().pid();
        long parentPid = Boolean.getBoolean(WRAPPED_PROPERTY)
                ? ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(-1L)
                : -1L;
        String markerEnv = System.getenv(MARKER_DIR_ENV);
        Path markerDir = markerEnv == null || markerEnv.isEmpty() ? null : Path.of(markerEnv);
        String baseName = role.name() + "-" + pid;

        Properties marker = new Properties();
        marker.setProperty("role", role.name());
        marker.setProperty("pid", Long.toString(pid));
        marker.setProperty("parentPid", Long.toString(parentPid));
        marker.setProperty("args", String.join("\n", args));

        String readyLine;
        if (role.isExporter()) {
            int port = listenPort(args, role.defaultPort);
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
            Files.move(tmp, markerDir.resolve(baseName + ".properties"),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        out.println(readyLine);
        out.flush();
        while (true) {
            Thread.sleep(Long.MAX_VALUE);
        }
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
            s.getOutputStream().write(
                    "HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
        } catch (IOException ignored) {
            // client went away
        }
    }
}
