package io.jenkins.plugins.onmonit.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import hudson.FilePath;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BinaryCacheTest {

    @TempDir
    Path tmp;

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void concurrentPopulatesBothSucceedWithOneFinalFile() throws Exception {
        FilePath keyDir = new FilePath(tmp.resolve("key").toFile());
        byte[] content = "binary".getBytes(StandardCharsets.UTF_8);
        CyclicBarrier bothWriting = new CyclicBarrier(2);
        BinaryCache.Writer writer = target -> {
            try (OutputStream out = target.write()) {
                out.write(content);
            }
            try {
                // both temp files are complete before either is moved into place
                bothWriting.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IOException(e);
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<BinaryCache.Result> a = pool.submit(() -> BinaryCache.populate(keyDir, "tool", writer));
            Future<BinaryCache.Result> b = pool.submit(() -> BinaryCache.populate(keyDir, "tool", writer));
            FilePath fa = a.get(30, TimeUnit.SECONDS).file();
            FilePath fb = b.get(30, TimeUnit.SECONDS).file();
            assertThat(fa.getRemote()).isEqualTo(fb.getRemote());
        } finally {
            pool.shutdownNow();
        }
        Path dir = tmp.resolve("key");
        assertThat(names(dir)).containsExactly("tool");
        assertThat(dir.resolve("tool")).hasBinaryContent(content);
    }

    @Test
    void existingFileIsAHit() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("key"));
        Path tool = Files.writeString(dir.resolve("tool"), "old");
        FileTime mtime = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));
        Files.setLastModifiedTime(tool, mtime);
        BinaryCache.Result result = BinaryCache.populate(new FilePath(dir.toFile()), "tool", target -> {
            throw new AssertionError("must not write on a cache hit");
        });
        assertThat(result.hit()).isTrue();
        assertThat(tool).hasContent("old");
        assertThat(Files.getLastModifiedTime(tool)).isEqualTo(mtime);
    }

    @Test
    void failedWriteLeavesNothingBehind() throws Exception {
        FilePath keyDir = new FilePath(tmp.resolve("key").toFile());
        assertThatThrownBy(() -> BinaryCache.populate(keyDir, "tool", target -> {
                    try (OutputStream out = target.write()) {
                        out.write(1);
                    }
                    throw new IOException("download failed");
                }))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("download failed");
        assertThat(names(tmp.resolve("key"))).isEmpty();
    }

    @Test
    void uploadKeyIsContentHashAndChangesWhenFileReplaced() throws Exception {
        Path file = Files.writeString(tmp.resolve("tool"), "v1");
        String k1 = BinaryCache.uploadKey(file);
        assertThat(k1).isEqualTo(BinaryCache.sha256Hex("v1"));
        assertThat(BinaryCache.uploadKey(file)).isEqualTo(k1);
        Files.writeString(file, "v2-longer");
        assertThat(BinaryCache.uploadKey(file)).isEqualTo(BinaryCache.sha256Hex("v2-longer"));
    }

    @Test
    void sha256HexIsLowercaseHex() {
        assertThat(BinaryCache.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
