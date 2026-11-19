package io.jenkins.plugins.onmonit.exec;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExecRemoteNodeExporterProcessTest {

    @Test
    void bindFailureOnLinuxIsPortInUse() {
        assertThat(ExecRemoteNodeExporterProcess.isPortInUse(
                        "level=ERROR msg=\"listen tcp :9100: bind: address already in use\""))
                .isTrue();
    }

    /** The real windows_exporter prints Go's {@code syscall.Errno} text for WSAEADDRINUSE (10048). */
    @Test
    void bindFailureOnWindowsIsPortInUse() {
        assertThat(ExecRemoteNodeExporterProcess.isPortInUse(
                        "level=ERROR msg=\"listen tcp :9182: bind: Only one usage of each socket address"
                                + " (protocol/network address/port) is normally permitted.\""))
                .isTrue();
    }

    @Test
    void otherFailureIsNotPortInUse() {
        assertThat(ExecRemoteNodeExporterProcess.isPortInUse("level=ERROR msg=\"unknown collector: foo\""))
                .isFalse();
    }
}
