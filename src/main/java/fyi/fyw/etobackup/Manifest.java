package fyi.fyw.etobackup;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;

/**
 * One snapshot: every backed up file with the slices it is made of. Stored as zstd-compressed text, a header of
 * {@code key value} lines, an empty line, then one line per file: {@code size TAB mtime TAB path TAB hash,hash,...}.
 * A file is the concatenation of its slices; a slice is the object named by the SHA-256 of its bytes.
 */
public final class Manifest {

    /** Size, modification time in milliseconds (0: must be read again next time) and slice hashes of one file. */
    public static final class FileEntry {

        private final long size;
        private final long mtime;
        private final String[] hashes;

        public FileEntry(long size, long mtime, String[] hashes) {
            this.size = size;
            this.mtime = mtime;
            this.hashes = hashes;
        }

        public long size() {
            return size;
        }

        public long mtime() {
            return mtime;
        }

        public String[] hashes() {
            return hashes;
        }
    }

    /** The header of a snapshot. {@code stored*}: what the run that made it had to add to the repository. */
    public static final class Summary {

        private final String name;
        private final int sliceSize;
        private final long files;
        private final long bytes;
        private final long storedObjects;
        private final long storedBytes;
        private final String kind;
        private final String note;

        public Summary(String name, int sliceSize, long files, long bytes, long storedObjects, long storedBytes,
            String kind, String note) {
            this.name = name;
            this.sliceSize = sliceSize;
            this.files = files;
            this.bytes = bytes;
            this.storedObjects = storedObjects;
            this.storedBytes = storedBytes;
            this.kind = kind;
            this.note = note;
        }

        public String name() {
            return name;
        }

        public int sliceSize() {
            return sliceSize;
        }

        public long files() {
            return files;
        }

        public long bytes() {
            return bytes;
        }

        public long storedObjects() {
            return storedObjects;
        }

        public long storedBytes() {
            return storedBytes;
        }

        /** {@link #AUTO}, {@link #MANUAL} or {@link #PRE_ROLLBACK}. */
        public String kind() {
            return kind;
        }

        /** One line on why the snapshot was taken, or empty. */
        public String note() {
            return note;
        }

        public String describe() {
            return name + "  "
                + files
                + " files, "
                + megabytes(bytes)
                + "; added "
                + storedObjects
                + " objects, "
                + megabytes(storedBytes);
        }
    }

    /** Taken by the timer. */
    public static final String AUTO = "auto";
    /** Taken on request. */
    public static final String MANUAL = "manual";
    /** The world as it was just before a rollback replaced it; what undoing that rollback goes back to. */
    public static final String PRE_ROLLBACK = "prerollback";

    private static final String MAGIC = "etobackup 1";
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC);
    private static final String[] NO_HASHES = new String[0];
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    public final String name;
    public final int sliceSize;
    /** By path relative to the server directory, with {@code /} separators. */
    public final Map<String, FileEntry> files = new TreeMap<>();
    public long storedObjects;
    public long storedBytes;
    public String kind = AUTO;
    public String note = "";

    public Manifest(String name, int sliceSize) {
        this.name = name;
        this.sliceSize = sliceSize;
    }

    public static String nameOf(Instant time) {
        return NAME.format(time);
    }

    public static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1e6);
    }

    /** @return the time in a snapshot name, or null when it is not one */
    public static Instant timeOf(String name) {
        try {
            return NAME.parse(name, Instant::from);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public Summary summary() {
        long bytes = 0;
        for (FileEntry entry : files.values()) bytes += entry.size;
        return new Summary(name, sliceSize, files.size(), bytes, storedObjects, storedBytes, kind, note);
    }

    byte[] encode() throws IOException {
        Summary summary = summary();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 16);
        try (Writer out = new OutputStreamWriter(new ZstdOutputStream(bytes, 3), StandardCharsets.UTF_8)) {
            out.write(MAGIC + "\nname " + name + "\nslice " + sliceSize + "\nfiles " + summary.files);
            out.write("\nbytes " + summary.bytes + "\nstored " + storedObjects + "\nstoredBytes " + storedBytes);
            // Readers skip the keys they do not know, so these need no new format version.
            out.write("\nkind " + kind);
            if (!note.isEmpty()) out.write("\nnote " + note.replace('\n', ' '));
            out.write("\n\n");
            for (Map.Entry<String, FileEntry> file : files.entrySet()) {
                FileEntry entry = file.getValue();
                out.write(entry.size + "\t" + entry.mtime + "\t" + file.getKey() + "\t");
                out.write(String.join(",", entry.hashes));
                out.write('\n');
            }
        }
        return bytes.toByteArray();
    }

    static Summary readSummary(Path file) throws IOException {
        try (BufferedReader in = open(file)) {
            return header(in, file);
        }
    }

    static Manifest read(Path file) throws IOException {
        Map<String, FileEntry> files = new TreeMap<>();
        Summary summary = scan(file, files::put);
        Manifest manifest = new Manifest(summary.name, summary.sliceSize);
        manifest.files.putAll(files);
        manifest.storedObjects = summary.storedObjects;
        manifest.storedBytes = summary.storedBytes;
        return manifest;
    }

    /** Passes every slice hash of the snapshot to {@code hashes} without keeping the files. */
    static void readHashes(Path file, Consumer<String> hashes) throws IOException {
        scan(file, (path, entry) -> {
            for (String hash : entry.hashes) hashes.accept(hash);
        });
    }

    private static Summary scan(Path file, BiConsumer<String, FileEntry> sink) throws IOException {
        try (BufferedReader in = open(file)) {
            Summary summary = header(in, file);
            long seen = 0;
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                String[] parts = line.split("\t", -1);
                if (parts.length != 4) throw new IOException("Bad file line in " + file + ": " + line);
                String[] hashes = parts[3].isEmpty() ? NO_HASHES : parts[3].split(",");
                // Hashes become file names.
                for (String hash : hashes) {
                    if (!HASH.matcher(hash)
                        .matches()) throw new IOException("Bad hash in " + file + ": " + hash);
                }
                sink.accept(parts[2], new FileEntry(Long.parseLong(parts[0]), Long.parseLong(parts[1]), hashes));
                seen++;
            }
            // The file is written whole and renamed into place; a short one was damaged later.
            if (seen != summary.files) {
                throw new IOException(file + " lists " + seen + " files, its header says " + summary.files);
            }
            return summary;
        } catch (NumberFormatException e) {
            throw new IOException("Bad number in " + file, e);
        }
    }

    private static BufferedReader open(Path file) throws IOException {
        return new BufferedReader(
            new InputStreamReader(new ZstdInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8));
    }

    private static Summary header(BufferedReader in, Path file) throws IOException {
        if (!MAGIC.equals(in.readLine())) throw new IOException(file + " is not a snapshot of a known version");
        String name = null;
        int slice = 0;
        long files = 0;
        long bytes = 0;
        long stored = 0;
        long storedBytes = 0;
        String kind = AUTO;
        String note = "";
        try {
            for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                int space = line.indexOf(' ');
                if (space < 0) throw new IOException("Bad header line in " + file + ": " + line);
                String value = line.substring(space + 1);
                switch (line.substring(0, space)) {
                    case "name" -> name = value;
                    case "slice" -> slice = Integer.parseInt(value);
                    case "files" -> files = Long.parseLong(value);
                    case "bytes" -> bytes = Long.parseLong(value);
                    case "stored" -> stored = Long.parseLong(value);
                    case "storedBytes" -> storedBytes = Long.parseLong(value);
                    case "kind" -> kind = value;
                    case "note" -> note = value;
                    default -> {}
                }
            }
        } catch (NumberFormatException e) {
            throw new IOException("Bad number in the header of " + file, e);
        }
        if (name == null || slice <= 0) throw new IOException(file + " has no name or slice size");
        return new Summary(name, slice, files, bytes, stored, storedBytes, kind, note);
    }
}
