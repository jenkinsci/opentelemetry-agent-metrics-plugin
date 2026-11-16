package io.jenkins.plugins.onmonit.testing;

import java.net.Inet6Address;
import java.net.InetAddress;

/** URLs for test servers bound to {@link InetAddress#getLoopbackAddress()}, naming that same address. */
public final class Loopback {

    private Loopback() {}

    /** {@code http://<loopback literal>:<port>}; {@code localhost} could resolve to the other IP family. */
    public static String httpUrl(int port) {
        return "http://" + hostLiteral(InetAddress.getLoopbackAddress()) + ":" + port;
    }

    /** The address as a URL host: IPv6 literals in brackets. */
    static String hostLiteral(InetAddress address) {
        String host = address.getHostAddress();
        return address instanceof Inet6Address ? "[" + host + "]" : host;
    }
}
