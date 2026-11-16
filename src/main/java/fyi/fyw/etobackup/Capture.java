package fyi.fyw.etobackup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the next snapshot from the files on disk. A file whose size and modification time match the previous
 * snapshot is taken over without being read; any other is read in slices, and the slices the previous snapshot does
 * not have go to the {@link Sink}. The caller keeps the game from writing the files meanwhile ({@link BackupGate});
 * a file that changes anyway is read again.
 */
public final class Capture {

    /** Receives each new slice once; {@code hash} is the SHA-256 of {@code data}. May block. */
    public interface Sink {

        void accept(String hash, byte[] data) throws IOException, InterruptedException;
    }

    /**
     * {@code unstable}: files that kept changing while they were read. {@code drifted}: files whose content had
     * changed although their size and time had not.
     */
    public static final class Result {

        private final Manifest manifest;
        private final int filesRead;
        private final long bytesRead;
        private final int newSlices;
        private final long newBytes;
        private final List<String> unstable;
        private final List<String> drifted;

        public Result(Manifest manifest, int filesRead, long bytesRead, int newSlices, long newBytes, List<String> unstable,
            List<String> drifted) {
            this.manifest = manifest;
            this.filesRead = filesRead;
            this.bytesRead = bytesRead;
            this.newSlices = newSlices;
            this.newBytes = newBytes;
            this.unstable = unstable;
            this.drifted = drifted;
        }

        public Manifest manifest() {
            return manifest;
        }

        public int filesRead() {
            return filesRead;
        }

        public long bytesRead() {
            return bytesRead;
        }

        public int newSlices() {
            return newSlices;
        }

        public long newBytes() {
            return newBytes;
        }

        public List<String> unstable() {
            return unstable;
        }

        public List<String> drifted() {
            return drifted;
        }
    }

    private static final int ATTEMPTS = 3;
    /** A file modified this close to the moment it was read is read again next time: the clocks are too coarse. */
    private static final long RACY_MILLIS = 100;
    /**
     * Each snapshot reads this fraction of the files that look unchanged anyway, a different set every time. A writer
     * that changes a file without its size or time showing it (a memory-mapped file, a file system that updates times
     * late) would otherwise leave a stale copy in every later snapshot; this bounds how long, and reports the file.
     */
    public static final int RECHECK_ONE_IN = 96;

    private final Path root;
    private final Manifest previous;
    private final Sink sink;
    private final Manifest manifest;
    private final Set<String> known = new HashSet<>();
    private final MessageDigest sha256 = BackupStore.sha256();
    private final byte[] buffer = new byte[BackupStore.SLICE_SIZE];
    private final List<String> unstable = new ArrayList<>();
    private final List<String> drifted = new ArrayList<>();
    private int filesRead;
    private long bytesRead;
    private int newSlices;
    private long newBytes;

    private Capture(Path root, Manifest previous, String name, Sink sink) {
        this.root = root;
        this.previous = previous != null && previous.sliceSize == BackupStore.SLICE_SIZE ? previous : null;
        this.sink = sink;
        this.manifest = new Manifest(name, BackupStore.SLICE_SIZE);
        if (this.previous != null) {
            for (Manifest.FileEntry entry : this.previous.files.values()) known.addAll(Arrays.asList(entry.hashes()));
        }
    }

    /**
     * @param root     the server directory
     * @param paths    files and directories under {@code root} to back up; missing ones are skipped
     * @param excluded paths under {@code root} to leave out, with everything below them
     * @param previous the newest snapshot, or null
     * @param name     the name of the new snapshot
     */
    public static Result run(Path root, List<String> paths, List<String> excluded, Manifest previous, String name,
        Sink sink) throws IOException, InterruptedException {
        Capture capture = new Capture(root, previous, name, sink);
        Set<Path> skip = new HashSet<>();
        for (String path : excluded) skip.add(root.resolve(path));
        List<Path> tops = new ArrayList<>();
        for (String path : paths) tops.add(root.resolve(path));
        try {
            for (Path top : tops) {
                if (!Files.exists(top)) continue;
                Files.walkFileTree(top, new SimpleFileVisitor<Path>() {

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        return skip.contains(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (attrs.isRegularFile() && !skip.contains(file)) capture.add(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                        // Deleted between being listed and being looked at.
                        if (e instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
                        throw e;
                    }
                });
            }
            // A file that was being replaced when its directory was listed (deleted, then renamed into place, as
            // the game does with player data) was not there to be seen.
            if (capture.previous != null) {
                for (String path : capture.previous.files.keySet()) {
                    if (capture.manifest.files.containsKey(path)) continue;
                    Path file = root.resolve(path);
                    if (covered(file, tops, skip) && Files.isRegularFile(file)) capture.add(file);
                }
            }
        } catch (Interrupted e) {
            throw (InterruptedException) e.getCause();
        }
        return new Result(
            capture.manifest,
            capture.filesRead,
            capture.bytesRead,
            capture.newSlices,
            capture.newBytes,
            capture.unstable,
            capture.drifted);
    }

    private static boolean covered(Path file, List<Path> tops, Set<Path> skip) {
        for (Path excluded : skip) if (file.startsWith(excluded)) return false;
        for (Path top : tops) if (file.startsWith(top)) return true;
        return false;
    }

    /** Carries an InterruptedException out of the file visitor. */
    private static final class Interrupted extends RuntimeException {

        Interrupted(InterruptedException cause) {
            super(cause);
        }
    }

    private void add(Path file) throws IOException {
        String path = root.relativize(file)
            .toString()
            .replace('\\', '/');
        if (path.indexOf('\t') >= 0 || path.indexOf('\n') >= 0) throw new IOException("Unsupported file name: " + path);
        // Not the attributes the directory listing came with: on Windows those are not updated while a writer has
        // the file open, and the server keeps its region files open.
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            return;
        }
        long size = attrs.size();
        long mtime = attrs.lastModifiedTime()
            .toMillis();
        Manifest.FileEntry old = previous == null ? null : previous.files.get(path);
        boolean unchanged = old != null && old.mtime() != 0 && old.size() == size && old.mtime() == mtime;
        if (unchanged && Math.floorMod((path + manifest.name).hashCode(), RECHECK_ONE_IN) != 0) {
            manifest.files.put(path, old);
            return;
        }
        for (int attempt = 1;; attempt++) {
            long started = System.currentTimeMillis();
            String[] hashes;
            try {
                hashes = read(file);
                BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class);
                boolean moved = after.size() != size || after.lastModifiedTime()
                    .toMillis() != mtime;
                size = after.size();
                mtime = after.lastModifiedTime()
                    .toMillis();
                if (moved && attempt < ATTEMPTS) continue;
                if (moved) unstable.add(path);
                else if (unchanged && !Arrays.equals(hashes, old.hashes())) drifted.add(path);
                if (moved || mtime >= started - RACY_MILLIS) mtime = 0;
            } catch (NoSuchFileException e) {
                return;
            }
            filesRead++;
            manifest.files.put(path, new Manifest.FileEntry(size, mtime, hashes));
            return;
        }
    }

    private String[] read(Path file) throws IOException {
        List<String> hashes = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file)) {
            while (true) {
                int length = 0;
                while (length < buffer.length) {
                    int count = in.read(buffer, length, buffer.length - length);
                    if (count < 0) break;
                    length += count;
                }
                if (length == 0) break;
                bytesRead += length;
                sha256.update(buffer, 0, length);
                String hash = BackupStore.hex(sha256.digest());
                hashes.add(hash);
                if (known.add(hash)) {
                    newSlices++;
                    newBytes += length;
                    try {
                        sink.accept(hash, Arrays.copyOf(buffer, length));
                    } catch (InterruptedException e) {
                        throw new Interrupted(e);
                    }
                }
                if (length < buffer.length) break;
            }
        }
        return hashes.toArray(new String[0]);
    }
}
