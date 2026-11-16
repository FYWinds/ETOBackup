package fyi.fyw.etobackup;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * The repository without a running server:
 *
 * <pre>
 * java -cp mods/etobackup-&lt;version&gt;.jar fyi.fyw.etobackup.BackupCli &lt;repository&gt; list
 * java -cp mods/etobackup-&lt;version&gt;.jar fyi.fyw.etobackup.BackupCli &lt;repository&gt; verify &lt;name|latest&gt;
 * java -cp mods/etobackup-&lt;version&gt;.jar fyi.fyw.etobackup.BackupCli &lt;repository&gt; restore &lt;name|latest&gt; &lt;empty directory&gt;
 * java -cp mods/etobackup-&lt;version&gt;.jar fyi.fyw.etobackup.BackupCli &lt;repository&gt; apply-pending &lt;server directory&gt;
 * </pre>
 *
 * {@code list}, {@code verify} and {@code restore} only read the repository, so they are safe next to a running
 * server. {@code apply-pending} carries out a world rollback asked for in the game ({@link Rollback}) and does
 * nothing when none was; the server does the same by itself when it starts, so running it is optional. It must never
 * run while the server does. When it exits with an error the world may be half replaced and the server must not
 * start; running it again goes on where it stopped.
 */
public final class BackupCli {

    private BackupCli() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2) usage();
        BackupStore store = new BackupStore(Paths.get(args[0]));
        switch (args[1]) {
            case "list" -> {
                for (String name : store.list()) System.out.println(store.summary(name).describe());
            }
            case "verify" -> {
                if (args.length != 3) usage();
                BackupStore.Verified result = store.verify(store.read(store.resolve(args[2])));
                for (String problem : result.problems()) System.out.println(problem);
                System.out.println(
                    result.objects() + " objects, "
                        + Manifest.megabytes(result.bytes())
                        + ", "
                        + result.problems()
                            .size()
                        + " damaged or missing");
                if (!result.problems()
                    .isEmpty()) System.exit(1);
            }
            case "restore" -> {
                if (args.length != 4) usage();
                Path dest = Paths.get(args[3]);
                Manifest.Summary summary = store.restore(store.read(store.resolve(args[2])), dest);
                System.out.println("Restored " + summary.describe() + " into " + dest.toAbsolutePath());
            }
            case "apply-pending" -> {
                if (args.length != 3) usage();
                Rollback.Outcome outcome = Rollback.apply(Paths.get(args[2]), store);
                if (outcome == null) return;
                System.out.println(
                    outcome.ok() ? "World rolled back to snapshot " + outcome.target()
                        : "World rollback to snapshot " + outcome.target()
                            + " failed, the world is unchanged: "
                            + outcome.message());
            }
            default -> usage();
        }
    }

    private static void usage() {
        for (String line : List.of(
            "usage: BackupCli <repository> list",
            "       BackupCli <repository> verify <name|latest>",
            "       BackupCli <repository> restore <name|latest> <empty directory>",
            "       BackupCli <repository> apply-pending <server directory>")) System.err.println(line);
        System.exit(2);
    }
}
