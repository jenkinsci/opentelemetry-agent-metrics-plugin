package io.jenkins.plugins.onmonit.testing;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** A localhost HTTP server serving fixed byte arrays by path; every other path gets 404. */
public final class DownloadServer implements AutoCloseable {

    private final HttpServer server;
    private final List<String> requested = new CopyOnWriteArrayList<>();

    private DownloadServer(HttpServer server) {
        this.server = server;
    }

    /** Starts a server on a free port; keys of {@code files} are request paths such as {@code /name}. */
    public static DownloadServer start(Map<String, byte[]> files) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        DownloadServer result = new DownloadServer(http);
        http.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            result.requested.add(path);
            byte[] body = files.get(path);
            try (exchange) {
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                }
            }
        });
        http.start();
        return result;
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** Paths requested so far, in arrival order. */
    public List<String> requestedPaths() {
        return List.copyOf(requested);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
