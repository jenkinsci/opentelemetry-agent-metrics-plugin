package io.jenkins.plugins.onmonit.testing;

import java.nio.file.Path;
import java.util.List;

/**
 * What a running {@link StandIn} recorded about itself.
 *
 * @param role the binary it stands in for
 * @param pid the stand-in JVM
 * @param parentPid the Windows launcher process, or {@code -1} when there is no separate launcher
 *     (the POSIX launcher {@code exec}s the JVM)
 * @param args the arguments the plugin passed
 * @param configCopy copy of the {@code --config=file:} file (otelcol only), else {@code null}
 * @param port the bound listen port (exporters only), else {@code null}
 */
public record Marker(StandIn.Role role, long pid, long parentPid, List<String> args, Path configCopy, Integer port) {}
