package io.jenkins.plugins.onmonit.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class LoopbackTest {

    @Test
    void ipv4LiteralIsBare() throws Exception {
        assertThat(Loopback.hostLiteral(InetAddress.getByName("127.0.0.1"))).isEqualTo("127.0.0.1");
    }

    @Test
    void ipv6LiteralIsBracketed() throws Exception {
        assertThat(Loopback.hostLiteral(InetAddress.getByName("::1"))).isEqualTo("[0:0:0:0:0:0:0:1]");
    }
}
