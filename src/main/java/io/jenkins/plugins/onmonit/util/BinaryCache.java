package io.jenkins.plugins.onmonit.util;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import jenkins.MasterToSlaveFileCallable;
import jenkins.util.SystemProperties;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches the executables provided to agents (uploaded from the controller or downloaded on the agent) under
 * {@code <agent root>/caches/opentelemetry-agent-metrics/<key>/<filename>}, so that they are transferred only once
 * per agent instead of once per build.
 * <p>
 * A cached file only ever appears under its final name after it was completely written (it is written to a unique
 * temporary name and then renamed), so its existence means it is valid. Cached files are never deleted by the plugin:
 * old keys stay so that a plugin rollback can reuse its version.
 */
public final class BinaryCache {

	/** System property (on the controller) to disable the cache; {@code false} restores the per-build copy. */
	public static final String ENABLED_PROPERTY = "io.jenkins.plugins.onmonit.cacheBinaries";

	private static final Map<Path, UploadKey> UPLOAD_KEYS = new ConcurrentHashMap<>();

	private BinaryCache() {
	}

	/** Writes an executable to a given location. */
	@FunctionalInterface
	public interface Writer {
		void writeTo(FilePath target) throws IOException, InterruptedException;
	}

	/** Creates the per-build temporary file used when the cache is not used. */
	@FunctionalInterface
	public interface TempFileFactory {
		FilePath create() throws IOException, InterruptedException;
	}

	/**
	 * The outcome of {@link #populate}.
	 *
	 * @param file the cached executable
	 * @param hit  {@code true} if the file was already cached, {@code false} if this call stored it
	 */
	public record Result(FilePath file, boolean hit) {
	}

	public static boolean isEnabled() {
		return SystemProperties.getBoolean(ENABLED_PROPERTY, true);
	}

	/**
	 * Provides the executable {@code filename}, from the agent cache if possible.
	 *
	 * @param temp        a directory on the agent (the build's temporary directory) used to find the agent
	 * @param listener    the build listener, for logging cache hits and populates
	 * @param displayName the name of the executable in log messages, e.g. {@code otelcol-contrib}
	 * @param key         the cache key, see {@link #uploadKey(Path)} and {@link #sha256Hex(String)}
	 * @param filename    the file name of the executable in the cache
	 * @param fallback    creates a per-build temporary file if the cache is disabled or unavailable; the caller
	 *                    is responsible for deleting that file
	 * @param writer      writes the executable
	 * @return the executable, marked executable
	 */
	public static FilePath obtain(FilePath temp, TaskListener listener, String displayName, String key, String filename,
			TempFileFactory fallback, Writer writer) throws IOException, InterruptedException {
		FilePath root = isEnabled() ? cacheRoot(temp) : null;
		if (root == null) {
			FilePath file = fallback.create();
			boolean written = false;
			try {
				writer.writeTo(file);
				file.chmod(0755);
				written = true;
			} finally {
				if (!written) {
					file.delete();
				}
			}
			return file;
		}
		Result result = populate(root.child(key), filename, writer);
		if (result.hit()) {
			listener.getLogger().println("[on-monit] Using cached " + displayName + " from " + result.file().getRemote());
		} else {
			listener.getLogger().println("[on-monit] Caching " + displayName + " in " + result.file().getRemote());
		}
		return result.file();
	}

	/**
	 * The cache directory {@code <agent root>/caches/opentelemetry-agent-metrics} of the agent {@code temp} is on,
	 * or {@code null} if the agent root is not known.
	 */
	@CheckForNull
	public static FilePath cacheRoot(FilePath temp) {
		Computer computer = temp.toComputer();
		if (computer == null) {
			return null;
		}
		Node node = computer.getNode();
		if (node == null) {
			return null;
		}
		FilePath root = node.getRootPath();
		if (root == null) {
			return null;
		}
		return root.child("caches").child("opentelemetry-agent-metrics");
	}

	/**
	 * Returns {@code keyDir/filename}, writing it with {@code writer} first if it does not exist yet. Safe against
	 * concurrent populates of the same file, also from different JVMs.
	 */
	public static Result populate(FilePath keyDir, String filename, Writer writer) throws IOException, InterruptedException {
		FilePath target = keyDir.child(filename);
		if (target.exists()) {
			return new Result(target, true);
		}
		keyDir.mkdirs();
		FilePath tmp = keyDir.child(filename + "." + UUID.randomUUID() + ".tmp");
		boolean moved = false;
		try {
			writer.writeTo(tmp);
			tmp.chmod(0755);
			keyDir.act(new MoveIntoPlace(tmp.getName(), filename));
			moved = true;
		} finally {
			if (!moved) {
				tmp.delete();
			}
		}
		return new Result(target, false);
	}

	/** A {@link Writer} that downloads {@code url} on the agent at the other end of {@code channel}. */
	public static Writer download(VirtualChannel channel, String url) {
		return target -> {
			try {
				channel.call(new DownloadOnSlaveCallable(url, target.getRemote()));
			} catch (IOException | InterruptedException | RuntimeException | Error e) {
				throw e;
			} catch (Throwable t) {
				throw new IOException("Could not download " + url, t);
			}
		};
	}

	/** The lowercase hex SHA-256 of the UTF-8 bytes of {@code s}, e.g. the cache key of a download URL. */
	public static String sha256Hex(String s) {
		return HexFormat.of().formatHex(sha256().digest(s.getBytes(StandardCharsets.UTF_8)));
	}

	/**
	 * The cache key of a controller-side file: the lowercase hex SHA-256 of its content. It is memoized per path,
	 * size and modification time, so the file is hashed again only when it is replaced.
	 */
	public static String uploadKey(Path file) throws IOException {
		Path path = file.toAbsolutePath();
		long size = Files.size(path);
		FileTime modified = Files.getLastModifiedTime(path);
		UploadKey known = UPLOAD_KEYS.get(path);
		if (known != null && known.size() == size && known.modified().equals(modified)) {
			return known.sha256();
		}
		MessageDigest digest = sha256();
		try (InputStream in = Files.newInputStream(path)) {
			byte[] buffer = new byte[65536];
			int read;
			while ((read = in.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
			}
		}
		String sha = HexFormat.of().formatHex(digest.digest());
		UPLOAD_KEYS.put(path, new UploadKey(size, modified, sha));
		return sha;
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}

	private record UploadKey(long size, FileTime modified, String sha256) {
	}

	/**
	 * Atomically renames a completely written temporary file to its final name. If that fails but the final file
	 * exists (a concurrent populate won, or Windows refuses to replace a running executable), the temporary file is
	 * discarded and the existing file is used.
	 */
	private static final class MoveIntoPlace extends MasterToSlaveFileCallable<Void> {

		@Serial
		private static final long serialVersionUID = 1L;

		private final String tmpName;

		private final String targetName;

		MoveIntoPlace(String tmpName, String targetName) {
			this.tmpName = tmpName;
			this.targetName = targetName;
		}

		@Override
		public Void invoke(File dir, VirtualChannel channel) throws IOException {
			Path tmp = dir.toPath().resolve(tmpName);
			Path target = dir.toPath().resolve(targetName);
			try {
				Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				if (!Files.exists(target)) {
					throw e;
				}
				Files.deleteIfExists(tmp);
			}
			return null;
		}
	}
}
