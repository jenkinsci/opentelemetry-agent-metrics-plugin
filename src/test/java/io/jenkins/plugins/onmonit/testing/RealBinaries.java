package io.jenkins.plugins.onmonit.testing;

import io.jenkins.plugins.onmonit.ResourceUtil;
import io.jenkins.plugins.onmonit.util.RemoteComputerInfoRetriever;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/**
 * Downloads the real otelcol-contrib and node_exporter / windows_exporter releases for the current OS and
 * architecture (cached under {@code ~/.cache/onmonit-test-binaries}) and installs them where the plugin's
 * upload path looks for them. Local-only; needs network on the first run.
 */
public final class RealBinaries {

    private static final Pattern VERSION = Pattern.compile("(\\d+\\.\\d+\\.\\d+)");

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
        String v = version(filename);
        String osName = "win".equals(os) ? "windows" : os;
        String arch = "win".equals(os) || amd64 ? "amd64" : "arm64";
        String archive = "otelcol-contrib_" + v + "_" + osName + "_" + arch + ".tar.gz";
        Path tgz = download(
                "https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/v" + v + "/"
                        + archive,
                archive);
        String entry = "win".equals(os) ? "otelcol-contrib.exe" : "otelcol-contrib";
        extract(tgz, entry, root.resolve(filename));
    }

    private static void installExporter(String os, boolean amd64, Path root) throws IOException {
        String filename = ResourceUtil.getNodeExporterFilename(os, amd64);
        String v = version(filename);
        Path target = root.resolve(filename);
        if ("win".equals(os)) {
            Path exe = download(
                    "https://github.com/prometheus-community/windows_exporter/releases/download/v" + v + "/" + filename,
                    filename);
            copyExecutable(exe, target);
            return;
        }
        String base = "node_exporter-" + v + "." + os + "-" + (amd64 ? "amd64" : "arm64");
        Path tgz = download(
                "https://github.com/prometheus/node_exporter/releases/download/v" + v + "/" + base + ".tar.gz",
                base + ".tar.gz");
        extract(tgz, base + "/node_exporter", target);
    }

    private static String version(String filename) {
        Matcher m = VERSION.matcher(filename);
        if (!m.find()) {
            throw new IllegalStateException("No version in " + filename);
        }
        return m.group(1);
    }

    private static Path download(String url, String name) throws IOException {
        Path cached = cacheDir().resolve(name);
        if (Files.isRegularFile(cached) && Files.size(cached) > 0) {
            return cached;
        }
        Path part = cacheDir().resolve(name + ".part");
        try (InputStream in = URI.create(url).toURL().openStream()) {
            Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw new IOException("Failed to download " + url, e);
        }
        Files.move(part, cached, StandardCopyOption.REPLACE_EXISTING);
        return cached;
    }

    private static void extract(Path tgz, String entryName, Path target) throws IOException {
        try (InputStream raw = Files.newInputStream(tgz);
                GzipCompressorInputStream gz = new GzipCompressorInputStream(raw);
                TarArchiveInputStream tar = new TarArchiveInputStream(gz)) {
            TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                String n = e.getName().startsWith("./") ? e.getName().substring(2) : e.getName();
                if (e.isFile() && n.equals(entryName)) {
                    Files.copy(tar, target, StandardCopyOption.REPLACE_EXISTING);
                    makeExecutable(target);
                    return;
                }
            }
        }
        throw new IOException(entryName + " not found in " + tgz);
    }

    private static void copyExecutable(Path source, Path target) throws IOException {
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        makeExecutable(target);
    }

    private static void makeExecutable(Path file) throws IOException {
        if (!file.toFile().setExecutable(true, false)) {
            throw new IOException("Cannot make executable: " + file);
        }
    }
}
