package io.jenkins.plugins.onmonit.testing;

import io.jenkins.plugins.onmonit.ResourceUtil;
import io.jenkins.plugins.onmonit.util.RemoteComputerInfoRetriever;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Builds launchers that start {@link StandIn} in place of the real {@code otelcol-contrib},
 * {@code node_exporter} and {@code windows_exporter} binaries, and reads what the stand-ins recorded.
 *
 * <p>On Linux and macOS a launcher is a shell script that {@code exec}s this JVM's {@code java}
 * with this JVM's class path. On Windows it is a small console executable compiled from
 * {@code StandInLauncher.cs} with PowerShell {@code Add-Type}.
 */
public final class StandIns {

    private static final boolean WINDOWS = RemoteComputerInfoRetriever.isWindows();
    private static final Map<StandIn.Role, byte[]> CACHE = new EnumMap<>(StandIn.Role.class);
    private static Boolean supported;
    private static RuntimeException unsupportedCause;
    private static Path windowsBuildDir;

    private StandIns() {}

    /**
     * Whether launchers can be built here. Always true on Linux/macOS; on Windows needs PowerShell {@code Add-Type}.
     * On Jenkins CI (environment variable {@code JENKINS_URL} set) an unusable toolchain is an error instead of
     * {@code false}, so a broken Windows agent cannot pass by skipping every stand-in test.
     */
    public static synchronized boolean supported() {
        if (supported == null) {
            if (!WINDOWS) {
                supported = true;
            } else {
                try {
                    bytesFor(StandIn.Role.OTELCOL);
                    supported = true;
                } catch (RuntimeException e) {
                    System.err.println("Stand-in launchers unsupported: " + e.getMessage());
                    unsupportedCause = e;
                    supported = false;
                }
            }
        }
        if (!supported && System.getenv("JENKINS_URL") != null) {
            throw new IllegalStateException("Stand-in launchers cannot be built on this Jenkins CI agent, so the "
                    + "stand-in tests would all be skipped: " + unsupportedCause.getMessage(), unsupportedCause);
        }
        return supported;
    }

    /** The exporter role for this OS: {@code windows_exporter} on Windows, else {@code node_exporter}. */
    public static StandIn.Role exporterRole() {
        return WINDOWS ? StandIn.Role.WINDOWS_EXPORTER : StandIn.Role.NODE_EXPORTER;
    }

    /** Writes the launchers for the current OS into {@code dir} under their PATH names and returns {@code dir}. */
    public static Path create(Path dir) throws IOException {
        Files.createDirectories(dir);
        if (WINDOWS) {
            write(dir.resolve("otelcol-contrib.exe"), StandIn.Role.OTELCOL);
            write(dir.resolve("windows_exporter.exe"), StandIn.Role.WINDOWS_EXPORTER);
        } else {
            write(dir.resolve("otelcol-contrib"), StandIn.Role.OTELCOL);
            write(dir.resolve("node_exporter"), StandIn.Role.NODE_EXPORTER);
        }
        return dir;
    }

    /**
     * Writes the launchers into {@code <jenkinsHome>/opentelemetry-agent-metrics/} under the
     * {@link ResourceUtil} file names for the current OS and architecture.
     */
    public static void writeResourceRoot(Path jenkinsHome) throws IOException {
        Path root = Files.createDirectories(jenkinsHome.resolve("opentelemetry-agent-metrics"));
        String os = RemoteComputerInfoRetriever.getOs();
        boolean amd64 = RemoteComputerInfoRetriever.isAmd64();
        write(root.resolve(ResourceUtil.getOtelFilename(os, amd64)), StandIn.Role.OTELCOL);
        write(root.resolve(ResourceUtil.getNodeExporterFilename(os, amd64)), exporterRole());
    }

    /** The launcher bytes for {@code role} on the current OS, e.g. for serving from a download server. */
    public static synchronized byte[] bytesFor(StandIn.Role role) {
        return CACHE.computeIfAbsent(role, r -> WINDOWS ? compileWindowsLauncher(r) : shellLauncher(r)).clone();
    }

    /** Reads all markers written into {@code markerDir} so far. */
    public static List<Marker> markers(Path markerDir) {
        List<Marker> result = new ArrayList<>();
        for (Properties p : markerProperties(markerDir)) {
            String args = p.getProperty("args", "");
            String configCopy = p.getProperty("configCopy");
            String port = p.getProperty("port");
            result.add(new Marker(
                    StandIn.Role.valueOf(p.getProperty("role")),
                    Long.parseLong(p.getProperty("pid")),
                    Long.parseLong(p.getProperty("parentPid", "-1")),
                    args.isEmpty() ? List.of() : List.of(args.split("\n", -1)),
                    configCopy == null ? null : Path.of(configCopy),
                    port == null ? null : Integer.valueOf(port)));
        }
        return result;
    }

    /**
     * Test cleanup: forcibly destroys every recorded stand-in and its launcher that is still alive, and waits
     * up to 10 seconds for them to exit (on Windows a process keeps its executable locked until it is gone).
     * A PID is only killed if the process holding it has the start instant recorded in the marker, so a PID
     * reused by an unrelated process is left alone; without a recorded start instant nothing is killed.
     */
    public static void killAll(Path markerDir) {
        List<ProcessHandle> doomed = new ArrayList<>();
        for (Properties p : markerProperties(markerDir)) {
            matching(p.getProperty("pid"), p.getProperty("startInstant")).ifPresent(doomed::add);
            matching(p.getProperty("parentPid"), p.getProperty("parentStartInstant")).ifPresent(doomed::add);
        }
        destroyAndWait(doomed);
    }

    /** Forcibly destroys {@code processes} and waits up to 10 seconds for all of them to exit. */
    static void destroyAndWait(List<ProcessHandle> processes) {
        List<CompletableFuture<ProcessHandle>> exits = new ArrayList<>();
        for (ProcessHandle h : processes) {
            h.destroyForcibly();
            exits.add(h.onExit());
        }
        try {
            CompletableFuture.allOf(exits.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            System.err.println("Stand-in processes did not exit within 10s: " + processes);
        }
    }

    private static Optional<ProcessHandle> matching(String pid, String startInstant) {
        if (pid == null || startInstant == null) {
            return Optional.empty();
        }
        long id = Long.parseLong(pid);
        if (id <= 0 || id == ProcessHandle.current().pid()) {
            return Optional.empty();
        }
        Instant expected = Instant.parse(startInstant);
        return ProcessHandle.of(id)
                .filter(h -> h.info().startInstant().map(expected::equals).orElse(false));
    }

    private static List<Properties> markerProperties(Path markerDir) {
        if (!Files.isDirectory(markerDir)) {
            return List.of();
        }
        List<Properties> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(markerDir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".properties")).sorted().toList()) {
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(f)) {
                    p.load(in);
                }
                result.add(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    private static void write(Path target, StandIn.Role role) throws IOException {
        Files.write(target, bytesFor(role));
        if (!WINDOWS) {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java").toString();
    }

    private static byte[] shellLauncher(StandIn.Role role) {
        String script = "#!/bin/sh\nexec " + shellQuote(javaExecutable())
                + " -cp " + shellQuote(System.getProperty("java.class.path"))
                + " " + StandIn.class.getName() + " " + role.name() + " \"$@\"\n";
        return script.getBytes(StandardCharsets.UTF_8);
    }

    /** Double-quotes {@code s} for POSIX sh. */
    private static String shellQuote(String s) {
        return '"' + s.replaceAll("([\"\\\\$`])", "\\\\$1") + '"';
    }

    private static synchronized byte[] compileWindowsLauncher(StandIn.Role role) {
        try {
            if (windowsBuildDir == null) {
                windowsBuildDir = Files.createTempDirectory("onmonit-standins");
                windowsBuildDir.toFile().deleteOnExit();
            }
            String template;
            try (InputStream in = StandIns.class.getResourceAsStream("StandInLauncher.cs")) {
                if (in == null) {
                    throw new IllegalStateException("StandInLauncher.cs not on the test class path");
                }
                template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String source = template
                    .replace("@JAVA@", verbatim(javaExecutable()))
                    .replace("@CP@", verbatim(pathingJar(windowsBuildDir).toString()))
                    .replace("@ROLE@", role.name());
            Path src = windowsBuildDir.resolve(role.name() + ".cs");
            Path out = windowsBuildDir.resolve(role.name() + ".exe");
            Files.writeString(src, source, StandardCharsets.UTF_8);
            src.toFile().deleteOnExit();
            out.toFile().deleteOnExit();
            String command = "Add-Type -OutputType ConsoleApplication -OutputAssembly " + psQuote(out.toString())
                    + " -TypeDefinition (Get-Content -Raw " + psQuote(src.toString()) + ")";
            Process ps = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", command)
                    .redirectErrorStream(true)
                    .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
                    .start();
            String output = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!ps.waitFor(2, TimeUnit.MINUTES)) {
                ps.destroyForcibly();
                throw new IllegalStateException("Add-Type timed out compiling the " + role + " launcher");
            }
            if (ps.exitValue() != 0 || !Files.isRegularFile(out)) {
                throw new IllegalStateException("Add-Type failed for the " + role + " launcher (exit "
                        + ps.exitValue() + "): " + output);
            }
            return Files.readAllBytes(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * A manifest-only jar whose {@code Class-Path} is this JVM's class path. The full test class path
     * is longer than the 32767-character Windows command-line limit, so it cannot go on {@code -cp}.
     */
    static synchronized Path pathingJar(Path dir) throws IOException {
        Path jar = dir.resolve("standin-classpath.jar");
        if (!Files.isRegularFile(jar)) {
            StringBuilder cp = new StringBuilder();
            for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
                if (!entry.isEmpty()) {
                    cp.append(cp.length() == 0 ? "" : " ").append(Path.of(entry).toAbsolutePath().toUri());
                }
            }
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, cp.toString());
            Path tmp = dir.resolve("standin-classpath.jar.tmp");
            new JarOutputStream(Files.newOutputStream(tmp), manifest).close();
            Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
            jar.toFile().deleteOnExit();
        }
        return jar;
    }

    private static java.io.File nullDevice() {
        return new java.io.File(WINDOWS ? "NUL" : "/dev/null");
    }

    /** Content of a C# verbatim string literal. */
    private static String verbatim(String s) {
        return s.replace("\"", "\"\"");
    }

    /** Single-quoted PowerShell string literal. */
    private static String psQuote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
