package fyi.fyw.etobackup

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where the repositories are, and the rollbacks still waiting in them. A rollback asked for in the game is carried out
 * while no world is loaded ([Rollback.apply]): a dedicated server carries out the request it stopped for when it is
 * started again, before mods are initialised; the client finishes a single-player rollback the game was killed in
 * when it starts, and checks a world's repository once more before opening that world.
 */
object PendingRollbacks {
    /** Thrown when a rollback stopped while the world was being swapped: the world must not be loaded like this. */
    class HalfReplaced(message: String, cause: Throwable) : IllegalStateException(message, cause)

    /** The repository of a dedicated server in the directory [root]. */
    fun serverRepository(root: Path, directory: String): Path = root.resolve(directory).toAbsolutePath().normalize()

    /** The directory holding the repository of every single-player world of the game directory [root]. */
    fun singlePlayerRoot(root: Path, directory: String): Path = serverRepository(root, directory)

    /** The repository of the single-player world in the folder `saves/<world>` of the game directory [root]. */
    fun singlePlayerRepository(root: Path, directory: String, world: String): Path =
        singlePlayerRoot(root, directory).resolve(world).normalize()

    /** What a single-player request names as its world, relative to the game directory. */
    fun singlePlayerWorld(world: String): String = "saves/$world"

    /**
     * Carries out the request in [store], if there is one, with [root] as the directory the request's world path is
     * relative to.
     *
     * @return how it ended, or null without a request
     * @throws HalfReplaced when it stopped during the swap; running this again goes on where it stopped
     */
    fun finish(root: Path, store: BackupStore): Rollback.Outcome? {
        val world = try {
            Rollback.pendingWorld(store) ?: return null
        } catch (e: IOException) {
            throw HalfReplaced("ETOBackup: the rollback request in ${store.dir()} cannot be read: $e", e)
        }
        LOG.info("Rollback: carrying out the request in {} for the world {}", store.dir(), world)
        val outcome = try {
            Rollback.apply(root, store)
        } catch (e: IOException) {
            throw HalfReplaced(
                "ETOBackup: the rollback of the world $world stopped halfway: $e. The world must not be loaded like " +
                    "this. Start again to finish it; the world as it was is in ${store.dir().resolve("rollback/old")} " +
                    "once it has been moved there.",
                e,
            )
        } ?: return null
        if (outcome.ok()) {
            LOG.info("Rollback: the world {} is back at snapshot {}; {} is the world before it", world, outcome.target(), outcome.pre())
        } else {
            LOG.warn("Rollback: the world {} was left as it was, the rollback to snapshot {} failed: {}", world, outcome.target(), outcome.message())
        }
        return outcome
    }

    /**
     * A dedicated server's start: carries out the request it stopped for.
     *
     * @throws HalfReplaced when the world was left half replaced; the server must not start
     */
    fun finishServer(root: Path, directory: String): Rollback.Outcome? =
        finish(root, BackupStore(serverRepository(root, directory)))

    /**
     * The game's start: finishes the single-player rollbacks the game was stopped in. A world whose rollback cannot
     * be finished is left alone here; opening it tries again ([finish]) and refuses while that fails.
     *
     * @return the folder names of the worlds whose rollback could not be finished
     */
    fun finishSinglePlayer(root: Path, directory: String): List<String> {
        val repositories = singlePlayerRoot(root, directory)
        if (!Files.isDirectory(repositories)) return emptyList()
        val failed = ArrayList<String>()
        val folders = try {
            Files.newDirectoryStream(repositories).use { entries -> entries.filter { Files.isDirectory(it) }.toList() }
        } catch (e: IOException) {
            LOG.error("Rollback: could not look for unfinished rollbacks in {}", repositories, e)
            return emptyList()
        }
        for (repository in folders) {
            val store = BackupStore(repository)
            val folder = repository.fileName.toString()
            try {
                val world = Rollback.pendingWorld(store) ?: continue
                if (world.replace('\\', '/') != singlePlayerWorld(folder)) {
                    LOG.warn("Rollback: ignoring the request in {}, it is for {} rather than {}", repository, world, singlePlayerWorld(folder))
                    continue
                }
                finish(root, store)
            } catch (e: HalfReplaced) {
                LOG.error("{}", e.message, e)
                failed += folder
            } catch (e: IOException) {
                LOG.error("Rollback: could not read the request in {}", repository, e)
                failed += folder
            }
        }
        return failed
    }
}
