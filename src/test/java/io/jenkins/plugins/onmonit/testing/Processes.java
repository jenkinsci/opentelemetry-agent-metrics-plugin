package io.jenkins.plugins.onmonit.testing;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs short-lived commands with a bound on the whole run, output included. */
public final class Processes {

    /** Exit code and combined stdout/stderr of a finished command. */
    public record Completed(int exitCode, String output) {}

    /** Commands get an empty stdin, so one that reads it (PowerShell can) does not wait for input. */
    private static final File NULL_DEVICE =
            new File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");

    private Processes() {}

    /**
     * Runs {@code command} and waits up to {@code timeout} for it to exit. Output goes to a temporary file rather than
     * a pipe, so a command that hangs (or leaves a child holding the pipe) cannot block the caller in a read. On
     * timeout the process is destroyed and an {@link AssertionError} carries the output so far.
     */
    public static Completed run(Duration timeout, List<String> command) throws IOException, InterruptedException {
        Path out = Files.createTempFile("onmonit-process", ".out");
        try {
            Process p = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(out.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(NULL_DEVICE))
                    .start();
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
                throw new AssertionError(command + " did not exit within " + timeout + "; output:\n" + read(out));
            }
            return new Completed(p.exitValue(), read(out));
        } finally {
            try {
                Files.deleteIfExists(out);
            } catch (IOException e) {
                // still held by a dying process on Windows; temp dir cleanup gets it later
                out.toFile().deleteOnExit();
            }
        }
    }

    /** Decodes leniently: malformed bytes (e.g. Windows OEM code page output) become U+FFFD instead of throwing. */
    private static String read(Path out) throws IOException {
        return new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
    }
}
