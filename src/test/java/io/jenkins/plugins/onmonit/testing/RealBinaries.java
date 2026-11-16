package io.jenkins.plugins.onmonit.testing;

import io.jenkins.plugins.onmonit.ResourceUtil;
import io.jenkins.plugins.onmonit.util.RemoteComputerInfoRetriever;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/**
 * Downloads the real otelcol-contrib and node_exporter / windows_exporter releases for the current OS and
 * architecture (cached under {@code ~/.cache/onmonit-test-binaries}) and installs them where the plugin's
 * upload path looks for them. Local-only; needs network on the first run.
 *
 * <p>Every archive is checked against the SHA-256 checksum file of its release, on download and on every cache hit, so
 * a truncated or stale cache entry is downloaded again instead of being trusted.
 */
public final class RealBinaries {

    // A stalled download fails instead of hanging @BeforeAll.
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    private RealBinaries() {}

    public static Path cacheDir() {
        return Path.of(System.getProperty("user.home"), ".cache", "onmonit-test-binaries");
    }

    public static void installResourceRoot(Path jenkinsHome) throws IOException {
        String os = RemoteComputerInfoRetriever.getOs();
        boolean amd64 = RemoteComputerInfoRetriever.isAmd64();
        Path root = jenkinsHome.resolve("opentelemetry-agent-metrics");
        Files.createDirectories(root);
        Files.createDirectories(cacheDir());

        installOtel(os, amd64, root);
        installExporter(os, amd64, root);
    }

    private static void installOtel(String os, boolean amd64, Path root) throws IOException {
        String filename = ResourceUtil.getOtelFilename(os, amd64);
        String v = Versions.of(filename);
        boolean windows = "win".equals(os);
        String osName = windows ? "windows" : os;
        String arch = windows || amd64 ? "amd64" : "arm64";
        String archive = "otelcol-contrib_" + v + "_" + osName + "_" + arch + ".tar.gz";
        String base = "https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/v" + v + "/";
        String sums = "opentelemetry-collector-releases_otelcol-contrib_" + (windows ? "windows_" : "") + "checksums.txt";
        Path tgz = download(base, archive, sums, "otelcol-contrib-" + v + "-" + sums);
        extract(tgz, windows ? "otelcol-contrib.exe" : "otelcol-contrib", root.resolve(filename));
    }

    private static void installExporter(String os, boolean amd64, Path root) throws IOException {
        String filename = ResourceUtil.getNodeExporterFilename(os, amd64);
        String v = Versions.of(filename);
        Path target = root.resolve(filename);
        if ("win".equals(os)) {
            String base = "https://github.com/prometheus-community/windows_exporter/releases/download/v" + v + "/";
            Path exe = download(base, filename, "sha256sums.txt", "windows_exporter-" + v + "-sha256sums.txt");
            Path staged = stagedFor(target);
            Files.copy(exe, staged, StandardCopyOption.REPLACE_EXISTING);
            installIfChanged(staged, target);
            return;
        }
        String name = "node_exporter-" + v + "." + os + "-" + (amd64 ? "amd64" : "arm64");
        String base = "https://github.com/prometheus/node_exporter/releases/download/v" + v + "/";
        Path tgz = download(base, name + ".tar.gz", "sha256sums.txt", "node_exporter-" + v + "-sha256sums.txt");
        extract(tgz, name + "/node_exporter", target);
    }

    /**
     * Returns the cached {@code asset} of the release at {@code baseUrl}, downloading it if it is missing or does not
     * match the release's checksum file {@code sumsAsset} (cached as {@code sumsCacheName}).
     */
    private static Path download(String baseUrl, String asset, String sumsAsset, String sumsCacheName)
            throws IOException {
        String expected = expectedSha256(fetch(baseUrl + sumsAsset, sumsCacheName), asset);
        Path cached = cacheDir().resolve(asset);
        if (Files.isRegularFile(cached) && expected.equalsIgnoreCase(sha256(cached))) {
            return cached;
        }
        Files.deleteIfExists(cached);
        fetch(baseUrl + asset, asset);
        String actual = sha256(cached);
        if (!expected.equalsIgnoreCase(actual)) {
            Files.delete(cached);
            throw new IOException("SHA-256 mismatch for " + asset + ": expected " + expected + ", got " + actual);
        }
        return cached;
    }

    /** Downloads {@code url} into the cache as {@code name} unless a non-empty copy is already there. */
    private static Path fetch(String url, String name) throws IOException {
        Path cached = cacheDir().resolve(name);
        if (Files.isRegularFile(cached) && Files.size(cached) > 0) {
            return cached;
        }
        Path part = cacheDir().resolve(name + ".part");
        URLConnection connection = URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        try (InputStream in = connection.getInputStream()) {
            Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw new IOException("Failed to download " + url, e);
        }
        Files.move(part, cached, StandardCopyOption.REPLACE_EXISTING);
        return cached;
    }

    /** The hex SHA-256 listed for {@code asset} in a {@code <sha256>  <name>} checksum file. */
    static String expectedSha256(Path checksums, String asset) throws IOException {
        for (String line : Files.readAllLines(checksums, StandardCharsets.UTF_8)) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && asset.equals(parts[1].startsWith("*") ? parts[1].substring(1) : parts[1])) {
                return parts[0];
            }
        }
        throw new IOException("No checksum for " + asset + " in " + checksums);
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                digest.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void extract(Path tgz, String entryName, Path target) throws IOException {
        try (InputStream raw = Files.newInputStream(tgz);
                GzipCompressorInputStream gz = new GzipCompressorInputStream(raw);
                TarArchiveInputStream tar = new TarArchiveInputStream(gz)) {
            TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                String n = e.getName().startsWith("./") ? e.getName().substring(2) : e.getName();
                if (e.isFile() && n.equals(entryName)) {
                    Path staged = stagedFor(target);
                    Files.copy(tar, staged, StandardCopyOption.REPLACE_EXISTING);
                    installIfChanged(staged, target);
                    return;
                }
            }
        }
        throw new IOException(entryName + " not found in " + tgz);
    }

    private static Path stagedFor(Path target) {
        return target.resolveSibling(target.getFileName() + ".new");
    }

    /**
     * Moves {@code staged} over {@code target} unless both have the same content, in which case {@code target} is left
     * alone: on Windows a binary still running from an earlier test cannot be replaced, but need not be. Either way
     * {@code staged} is gone afterwards and {@code target} is executable.
     */
    static void installIfChanged(Path staged, Path target) throws IOException {
        if (Files.isRegularFile(target) && Files.mismatch(staged, target) == -1) {
            Files.delete(staged);
        } else {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
        }
        makeExecutable(target);
    }

    private static void makeExecutable(Path file) throws IOException {
        if (!file.toFile().setExecutable(true, false)) {
            throw new IOException("Cannot make executable: " + file);
        }
    }
}
