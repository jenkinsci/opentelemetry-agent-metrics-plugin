package io.jenkins.plugins.onmonit.testing;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Runs a test class only while no other test JVM on this host is inside a class with this extension. For classes
 * whose builds let the plugin pick node_exporter's port (default 9100) or start real binaries (otelcol telemetry on
 * 8888).
 *
 * <p>The plugin picks a port by binding and closing it, and serializes that only within one controller. Two test runs
 * at once (e.g. parallel worktrees) can therefore both pick 9100, and one stand-in then fails with
 * {@code BindException}. The lock is an OS file lock in {@code java.io.tmpdir}, held for the whole class; the OS drops
 * it when the JVM exits, however it exits, so a crashed run leaves no stale lock.
 */
public final class DefaultPortLock implements BeforeAllCallback, AfterAllCallback {

    /** Exit code of {@code main("try", file)} when another process holds the lock. */
    static final int BUSY = 3;

    static final Path FILE = Path.of(System.getProperty("java.io.tmpdir"), "onmonit-test-default-ports.lock");
    private static final Duration TIMEOUT = Duration.ofMinutes(30);
    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(DefaultPortLock.class);

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        context.getStore(NS).put(Held.class, acquire(FILE, TIMEOUT));
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        Held held = context.getStore(NS).remove(Held.class, Held.class);
        if (held != null) {
            held.close();
        }
    }

    /** Waits up to {@code timeout} for the lock on {@code file}. */
    static Held acquire(Path file, Duration timeout) throws IOException, InterruptedException {
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            Instant deadline = Instant.now().plus(timeout);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                System.err.println("Waiting for another test run to release " + file);
            }
            while (lock == null) {
                if (Instant.now().isAfter(deadline)) {
                    throw new IllegalStateException("Another test run held " + file + " for over " + timeout);
                }
                Thread.sleep(200);
                lock = channel.tryLock();
            }
            return new Held(channel, lock);
        } catch (IOException | InterruptedException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    /** A held lock; closing releases it. */
    record Held(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            channel.close(); // also releases the lock
        }
    }

    /**
     * For tests, run in another JVM; exits {@link #BUSY} if another process holds the lock on {@code file}. Otherwise
     * {@code try <file>} exits 0, and {@code hold <file>} writes {@code L} to stdout and holds the lock until killed.
     *
     * <p>Uses only the JDK (unlike the enclosing extension, which needs JUnit to load), so the other JVM needs just the
     * test-classes directory on its class path. The full test class path is longer than Windows allows on a command
     * line (CreateProcess error=206).
     */
    public static final class Probe {
        private Probe() {}

        public static void main(String[] args) throws Exception {
            Path file = Path.of(args[1]);
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    System.exit(BUSY);
                }
                if (args[0].equals("try")) {
                    System.exit(0);
                }
                System.out.print('L');
                System.out.flush();
                Thread.sleep(Duration.ofMinutes(5).toMillis());
            }
        }
    }
}
