package fyi.fyw.etobackup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Replacing the world with a snapshot. A dedicated server never does it while it runs: it writes a request into the
 * repository ({@code pending-rollback}) and stops, and {@link #apply} carries the request out from the start script,
 * before the game starts and while nothing has the world open:
 *
 * <pre>
 * java -cp mods/etobackup-&lt;version&gt;.jar fyi.fyw.etobackup.BackupCli &lt;repository&gt; apply-pending &lt;server directory&gt;
 * </pre>
 *
 * In single player the game itself calls {@link #apply} once the world is closed and its server has stopped.
 *
 * The steps, any of which may be interrupted and is then picked up by the next run:
 * <ol>
 * <li>The snapshot's world files are written to {@code rollback/new} in the repository, every slice checked against
 * its hash. A failure up to here leaves the world as it is and drops the request.</li>
 * <li>The world directory is moved to {@code rollback/old}.</li>
 * <li>The restored directory is moved to where the world was.</li>
 * <li>The result goes to {@code last-rollback} and the request is removed.</li>
 * </ol>
 *
 * Only the world directory is replaced; whatever else the snapshot holds stays as it is on disk.
 */
public final class Rollback {

    /** How the last request ended. */
    public static final class Outcome {

        private final String target;
        private final String pre;
        private final String by;
        private final long time;
        private final boolean ok;
        private final String message;

        Outcome(String target, String pre, String by, long time, boolean ok, String message) {
            this.target = target;
            this.pre = pre;
            this.by = by;
            this.time = time;
            this.ok = ok;
            this.message = message;
        }

        /** The snapshot the world was to become. */
        public String target() {
            return target;
        }

        /** The snapshot taken just before; going back to it undoes the rollback. */
        public String pre() {
            return pre;
        }

        public String by() {
            return by;
        }

        /** When it ended, in milliseconds. */
        public long time() {
            return time;
        }

        public boolean ok() {
            return ok;
        }

        /** Why it failed, or empty. */
        public String message() {
            return message;
        }
    }

    private static final String PENDING = "pending-rollback";
    private static final String LAST = "last-rollback";
    private static final String APPLIER = "applier-ran";
    private static final String SWAP = "swap";

    private Rollback() {}

    /** Asks for {@code world} (relative to the server directory) to become the snapshot {@code target}. */
    public static void request(BackupStore store, String target, String pre, String by, String world)
        throws IOException {
        Map<String, String> request = new LinkedHashMap<>();
        request.put("target", target);
        request.put("pre", pre);
        request.put("by", by);
        request.put("world", world);
        request.put("step", "restore");
        write(store.dir()
            .resolve(PENDING), request);
    }

    /** @return how the last request ended, or null when there never was one */
    public static Outcome last(BackupStore store) throws IOException {
        Map<String, String> last = read(store.dir()
            .resolve(LAST));
        if (last == null) return null;
        try {
            return new Outcome(
                last.getOrDefault("target", ""),
                last.getOrDefault("pre", ""),
                last.getOrDefault("by", ""),
                Long.parseLong(last.getOrDefault("time", "0")),
                "ok".equals(last.get("result")),
                last.getOrDefault("message", ""));
        } catch (NumberFormatException e) {
            throw new IOException("Bad time in " + LAST, e);
        }
    }

    /**
     * Carries out the request, if there is one. An exception means the world may be half replaced: the server must
     * not start, and running this again goes on where it stopped.
     *
     * @return how it ended, or null when there was no request
     */
    public static Outcome apply(Path serverDir, BackupStore store) throws IOException {
        Path marker = store.dir()
            .resolve(PENDING);
        Map<String, String> request = read(marker);
        if (request == null) return null;
        String worldName = request.getOrDefault("world", "");
        Path root = serverDir.toAbsolutePath()
            .normalize();
        Path world = root.resolve(worldName)
            .normalize();
        Path work = store.dir()
            .resolve("rollback");
        Path staged = work.resolve("new");
        Path old = work.resolve("old");
        if (!SWAP.equals(request.get("step"))) {
            try {
                if (worldName.isEmpty() || world.equals(root) || !world.startsWith(root)) {
                    throw new IOException("the world directory " + worldName + " is not inside " + root);
                }
                if (!Files.isDirectory(world)) throw new IOException("there is no world directory " + world);
                deleteTree(staged);
                deleteTree(old);
                Files.createDirectories(staged);
                // The moves below must be renames, which cannot fail halfway.
                if (!Files.getFileStore(staged)
                    .equals(Files.getFileStore(world))) {
                    throw new IOException("the repository and the world are on different file systems");
                }
                Manifest manifest = store.read(request.getOrDefault("target", ""));
                String prefix = worldName.replace('\\', '/') + "/";
                if (store.restore(manifest, staged, prefix) == 0) {
                    throw new IOException("snapshot " + manifest.name + " has no files of " + worldName);
                }
            } catch (IOException e) {
                deleteTree(staged);
                return finish(store, request, false, String.valueOf(e.getMessage()));
            }
            request.put("step", SWAP);
            write(marker, request);
        }
        Path restored = staged.resolve(worldName);
        if (Files.exists(restored)) {
            if (Files.exists(world)) Files.move(world, old, StandardCopyOption.ATOMIC_MOVE);
            Files.move(restored, world, StandardCopyOption.ATOMIC_MOVE);
        }
        deleteTree(staged);
        return finish(store, request, true, "");
    }

    /**
     * For the server, which finds a request still there when it starts: the start script did not run {@link #apply}.
     * The request is dropped so that a later start does not carry it out long after it was made.
     *
     * @return what was recorded, or null when there was no request
     * @throws IllegalStateException when the world is half replaced
     */
    public static Outcome dropUnapplied(BackupStore store) throws IOException {
        Map<String, String> request = read(store.dir()
            .resolve(PENDING));
        if (request == null) return null;
        if (SWAP.equals(request.get("step"))) {
            throw new IllegalStateException(
                "A world rollback was interrupted halfway. Run BackupCli apply-pending before starting the server.");
        }
        return finish(store, request, false, "the start script does not run BackupCli apply-pending");
    }

    /** Called by the start script's step on every start, so that the server can tell the step is there. */
    public static void markApplierRan(BackupStore store) throws IOException {
        Files.createDirectories(store.dir());
        Files.write(
            store.dir()
                .resolve(APPLIER),
            new byte[0]);
    }

    /** Whether the start script's step ran before this start of the server; true only the first time it is asked. */
    public static boolean takeApplierRan(BackupStore store) throws IOException {
        return Files.deleteIfExists(
            store.dir()
                .resolve(APPLIER));
    }

    /** Deletes the world a rollback replaced, once the server runs on the new one. */
    public static void deleteReplaced(BackupStore store) throws IOException {
        deleteTree(
            store.dir()
                .resolve("rollback"));
    }

    private static Outcome finish(BackupStore store, Map<String, String> request, boolean ok, String message)
        throws IOException {
        Map<String, String> last = new LinkedHashMap<>();
        last.put("target", request.getOrDefault("target", ""));
        last.put("pre", request.getOrDefault("pre", ""));
        last.put("by", request.getOrDefault("by", ""));
        last.put("time", Long.toString(System.currentTimeMillis()));
        last.put("result", ok ? "ok" : "failed");
        last.put("message", message.replace('\n', ' '));
        write(store.dir()
            .resolve(LAST), last);
        Files.delete(
            store.dir()
                .resolve(PENDING));
        return last(store);
    }

    /** {@code key value} lines, or null without the file. */
    private static Map<String, String> read(Path file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int space = line.indexOf(' ');
                if (space > 0) values.put(line.substring(0, space), line.substring(space + 1));
            }
        } catch (NoSuchFileException e) {
            return null;
        }
        return values;
    }

    private static void write(Path file, Map<String, String> values) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> value : values.entrySet()) {
            text.append(value.getKey())
                .append(' ')
                .append(value.getValue())
                .append('\n');
        }
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(
            temporary,
            text.toString()
                .getBytes(StandardCharsets.UTF_8));
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException e) throws IOException {
                if (e != null) throw e;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
