package fyi.fyw.etobackup;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.github.luben.zstd.Zstd;

/**
 * The backup repository, a directory:
 *
 * <pre>
 * objects/ab/abcdef....zst    one slice of a file, zstd-compressed, named by the SHA-256 of its bytes
 * snapshots/&lt;name&gt;.txt.zst    one {@link Manifest} per snapshot
 * repo-id                     a random id, so that a client can tell this server's snapshots from another's
 * </pre>
 *
 * Objects are never changed once written and a snapshot only appears after every object it names is on disk, so a
 * run that dies leaves at most objects no snapshot names; {@link #collectGarbage} removes those. Restoring by hand:
 * for each line of a manifest ({@code zstd -dc}), concatenate {@code zstd -dc objects/<first two>/<hash>.zst}.
 *
 * Not thread safe: one thread at a time.
 */
public final class BackupStore {

    /** Region files are rewritten in place in 4 KiB sectors; slices are a multiple of that so they stay aligned. */
    public static final int SLICE_SIZE = 64 * 1024;

    private static final int LEVEL = 3;
    private static final String OBJECT_SUFFIX = ".zst";
    private static final String MANIFEST_SUFFIX = ".txt.zst";

    /** What {@link #verify} read and what was wrong with it. */
    public static final class Verified {

        private final long objects;
        private final long bytes;
        private final List<String> problems;

        public Verified(long objects, long bytes, List<String> problems) {
            this.objects = objects;
            this.bytes = bytes;
            this.problems = problems;
        }

        public long objects() {
            return objects;
        }

        public long bytes() {
            return bytes;
        }

        public List<String> problems() {
            return problems;
        }
    }

    private final Path dir;
    private final Path objects;
    private final Path snapshots;
    private final MessageDigest sha256 = sha256();

    /** Written since the last commit and not yet forced to disk. */
    private final List<Path> pending = new ArrayList<>();
    private final Set<Path> pendingDirs = new HashSet<>();
    private long pendingBytes;

    public BackupStore(Path dir) {
        this.dir = dir;
        this.objects = dir.resolve("objects");
        this.snapshots = dir.resolve("snapshots");
    }

    public Path dir() {
        return dir;
    }

    /** The repository's id, made the first time it is asked for. */
    public String id() throws IOException {
        Path file = dir.resolve("repo-id");
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
        } catch (NoSuchFileException e) {
            String id = UUID.randomUUID()
                .toString();
            Files.createDirectories(dir);
            Files.write(file, id.getBytes(StandardCharsets.UTF_8));
            return id;
        }
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] digest) {
        char[] out = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            out[2 * i] = Character.forDigit((digest[i] >> 4) & 15, 16);
            out[2 * i + 1] = Character.forDigit(digest[i] & 15, 16);
        }
        return new String(out);
    }

    /** Snapshot names, oldest first. */
    public List<String> list() throws IOException {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(snapshots)) return names;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(snapshots, "*" + MANIFEST_SUFFIX)) {
            for (Path file : files) {
                String name = file.getFileName()
                    .toString();
                name = name.substring(0, name.length() - MANIFEST_SUFFIX.length());
                if (Manifest.timeOf(name) != null) names.add(name);
            }
        }
        Collections.sort(names);
        return names;
    }

    public Manifest read(String name) throws IOException {
        return Manifest.read(manifestFile(name));
    }

    public Manifest.Summary summary(String name) throws IOException {
        return Manifest.readSummary(manifestFile(name));
    }

    /** The name itself, or the newest snapshot for {@code latest}. */
    public String resolve(String name) throws IOException {
        if (!"latest".equals(name)) {
            if (!Files.isRegularFile(manifestFile(name))) throw new NoSuchFileException("No snapshot " + name);
            return name;
        }
        List<String> names = list();
        if (names.isEmpty()) throw new NoSuchFileException("The repository has no snapshots");
        return names.get(names.size() - 1);
    }

    /** Stores one slice; {@code hash} is the SHA-256 of {@code data}. */
    public void put(String hash, byte[] data) throws IOException {
        byte[] compressed = Zstd.compress(data, LEVEL);
        Path file = objectFile(hash);
        Path parent = file.getParent();
        if (pendingDirs.add(parent)) Files.createDirectories(parent);
        // Never write under the final name: an object with that name may be in use by older snapshots.
        Path temporary = parent.resolve(hash + ".tmp");
        Files.write(temporary, compressed);
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        pending.add(file);
        pendingBytes += compressed.length;
    }

    /** Forces the objects written since the last commit to disk, then makes the snapshot appear. */
    public void commit(Manifest manifest) throws IOException {
        for (Path file : pending) force(file);
        for (Path parent : pendingDirs) forceDirectory(parent);
        if (!pendingDirs.isEmpty()) forceDirectory(objects);
        manifest.storedObjects = pending.size();
        manifest.storedBytes = pendingBytes;
        Files.createDirectories(snapshots);
        Path file = manifestFile(manifest.name);
        Path temporary = snapshots.resolve(manifest.name + ".tmp");
        try (FileChannel channel = FileChannel
            .open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = ByteBuffer.wrap(manifest.encode());
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        forceDirectory(snapshots);
        abandon();
    }

    /** Forgets the objects written since the last commit; they stay on disk until the next garbage collection. */
    public void abandon() {
        pending.clear();
        pendingDirs.clear();
        pendingBytes = 0;
    }

    /** Writes every file of the snapshot under {@code dest}, checking each slice against its hash. */
    public Manifest.Summary restore(Manifest manifest, Path dest) throws IOException {
        restore(manifest, dest, "");
        return manifest.summary();
    }

    /**
     * Like {@link #restore(Manifest, Path)}, but only the files whose path starts with {@code prefix}.
     *
     * @return the number of files written
     */
    public long restore(Manifest manifest, Path dest, String prefix) throws IOException {
        if (Files.exists(dest)) {
            try (DirectoryStream<Path> content = Files.newDirectoryStream(dest)) {
                if (content.iterator()
                    .hasNext()) throw new IOException(dest + " is not empty");
            }
        }
        Path root = dest.toAbsolutePath()
            .normalize();
        long count = 0;
        for (Map.Entry<String, Manifest.FileEntry> file : manifest.files.entrySet()) {
            if (!file.getKey()
                .startsWith(prefix)) continue;
            count++;
            Path target = root.resolve(file.getKey())
                .normalize();
            if (!target.startsWith(root)) throw new IOException("Path leaves the destination: " + file.getKey());
            Files.createDirectories(target.getParent());
            long written = 0;
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target), SLICE_SIZE)) {
                for (String hash : file.getValue()
                    .hashes()) {
                    byte[] slice = get(hash, manifest.sliceSize);
                    out.write(slice);
                    written += slice.length;
                }
            }
            if (written != file.getValue()
                .size()) {
                throw new IOException(
                    file.getKey() + " restored to " + written + " bytes, expected " + file.getValue().size());
            }
        }
        return count;
    }

    /** Reads every object the snapshot names and checks it against its hash. */
    public Verified verify(Manifest manifest) {
        Set<String> seen = new HashSet<>();
        List<String> problems = new ArrayList<>();
        long bytes = 0;
        for (Manifest.FileEntry entry : manifest.files.values()) {
            for (String hash : entry.hashes()) {
                if (!seen.add(hash)) continue;
                try {
                    bytes += get(hash, manifest.sliceSize).length;
                } catch (IOException e) {
                    problems.add(e.getMessage());
                }
            }
        }
        return new Verified(seen.size(), bytes, problems);
    }

    /**
     * Deletes the snapshots {@code policy} does not keep. A snapshot taken before a rollback stays for
     * {@code preRollbackDays} days (counted back from the newest snapshot) whatever the policy says, so that the
     * rollback can be undone.
     *
     * @return the names of the deleted snapshots
     */
    public List<String> prune(Retention.Policy policy, ZoneId zone, int preRollbackDays) throws IOException {
        List<String> names = list();
        if (names.isEmpty()) return names;
        Set<String> keep = Retention.keep(names, policy, zone);
        Instant pinnedFrom = Manifest.timeOf(names.get(names.size() - 1))
            .minus(preRollbackDays, ChronoUnit.DAYS);
        List<String> deleted = new ArrayList<>();
        for (String name : names) {
            if (keep.contains(name)) continue;
            if (!Manifest.timeOf(name)
                .isBefore(pinnedFrom) && Manifest.PRE_ROLLBACK.equals(summary(name).kind())) continue;
            Files.delete(manifestFile(name));
            deleted.add(name);
        }
        return deleted;
    }

    /**
     * Deletes every object no snapshot names. Must not run while objects of a snapshot that is not committed yet are
     * being written. A snapshot that cannot be read stops it before anything is deleted.
     *
     * @return the number of files deleted and their bytes
     */
    public long[] collectGarbage() throws IOException {
        Set<String> live = new HashSet<>();
        for (String name : list()) Manifest.readHashes(manifestFile(name), live::add);
        long count = 0;
        long bytes = 0;
        if (!Files.isDirectory(objects)) return new long[] { 0, 0 };
        try (DirectoryStream<Path> groups = Files.newDirectoryStream(objects)) {
            for (Path group : groups) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(group)) {
                    for (Path file : files) {
                        String name = file.getFileName()
                            .toString();
                        if (name.endsWith(OBJECT_SUFFIX)
                            && live.contains(name.substring(0, name.length() - OBJECT_SUFFIX.length()))) continue;
                        bytes += Files.size(file);
                        Files.delete(file);
                        count++;
                    }
                }
            }
        }
        return new long[] { count, bytes };
    }

    /** @return the number of snapshots, and the bytes all snapshots and objects take on disk */
    public long[] usage() throws IOException {
        long snapshotCount = 0;
        long bytes = 0;
        if (Files.isDirectory(snapshots)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(snapshots, "*" + MANIFEST_SUFFIX)) {
                for (Path file : files) {
                    bytes += Files.size(file);
                    snapshotCount++;
                }
            }
        }
        if (Files.isDirectory(objects)) {
            try (DirectoryStream<Path> groups = Files.newDirectoryStream(objects)) {
                for (Path group : groups) {
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(group)) {
                        for (Path file : files) bytes += Files.size(file);
                    }
                }
            }
        }
        return new long[] { snapshotCount, bytes };
    }

    private byte[] get(String hash, int sliceSize) throws IOException {
        byte[] compressed;
        try {
            compressed = Files.readAllBytes(objectFile(hash));
        } catch (NoSuchFileException e) {
            throw new IOException("Object " + hash + " is missing");
        }
        byte[] data;
        try {
            long size = Zstd.getFrameContentSize(compressed);
            if (size < 0 || size > sliceSize) throw new IOException("Object " + hash + " is damaged");
            data = Zstd.decompress(compressed, (int) size);
        } catch (RuntimeException e) {
            // zstd-jni reports bad input with an unchecked exception.
            throw new IOException("Object " + hash + " is damaged: " + e.getMessage());
        }
        if (!hash.equals(hex(sha256.digest(data)))) throw new IOException("Object " + hash + " is damaged");
        return data;
    }

    private Path objectFile(String hash) {
        return objects.resolve(hash.substring(0, 2))
            .resolve(hash + OBJECT_SUFFIX);
    }

    private Path manifestFile(String name) {
        return snapshots.resolve(name + MANIFEST_SUFFIX);
    }

    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    /** Makes new directory entries durable. Windows cannot open a directory this way and does not need it. */
    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            // Not supported here.
        }
    }
}
