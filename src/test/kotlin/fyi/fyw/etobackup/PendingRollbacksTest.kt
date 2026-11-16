package fyi.fyw.etobackup

import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PendingRollbacksTest {
    @TempDir
    lateinit var temp: Path

    private val random = Random(5)

    private fun file(root: Path, path: String, size: Int) {
        val file = root.resolve(path)
        file.parent.createDirectories()
        file.writeBytes(ByteArray(size).also(random::nextBytes))
    }

    private fun content(directory: Path): Map<String, List<Byte>> =
        directory.toFile().walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(directory.toFile()).invariantSeparatorsPath to it.readBytes().toList() }

    /** A snapshot [TARGET] of [world] (relative to [root]) in [store], then the world changed, then a request back to it. */
    private fun requested(root: Path, store: BackupStore, world: String): Pair<Map<String, List<Byte>>, Map<String, List<Byte>>> {
        file(root, "$world/level.dat", 900)
        file(root, "$world/region/r.0.0.mca", 100 * 1024)
        val manifest = Capture.run(root, listOf(world), emptyList(), null, TARGET, store::put).manifest()
        store.commit(manifest)
        val then = content(root.resolve(world))
        file(root, "$world/level.dat", 950)
        file(root, "$world/region/r.1.0.mca", 3_000)
        val now = content(root.resolve(world))
        Rollback.request(store, TARGET, PRE, "tester", world)
        return then to now
    }

    /** Leaves the request where a run killed after restoring, before the first move, left it. */
    private fun killedBeforeTheSwap(store: BackupStore, world: String) {
        store.restore(store.read(TARGET), store.dir().resolve("rollback/new"), "$world/")
        val marker = store.dir().resolve("pending-rollback")
        marker.writeText(marker.readText().replace("step restore", "step swap"))
    }

    @Test
    fun `a dedicated server's repository is the directory option, relative to the server directory or absolute`() {
        val server = temp.resolve("server")
        val elsewhere = temp.resolve("elsewhere").toAbsolutePath()

        assertThat(PendingRollbacks.serverRepository(server, "eto-backups"))
            .isEqualTo(server.resolve("eto-backups").toAbsolutePath())
        assertThat(PendingRollbacks.serverRepository(server, elsewhere.toString())).isEqualTo(elsewhere)
    }

    @Test
    fun `each single-player world has its own repository below the directory option, outside its world`() {
        val game = temp.resolve(".minecraft")
        val first = PendingRollbacks.singlePlayerRepository(game, "eto-backups", "New World")
        val second = PendingRollbacks.singlePlayerRepository(game, "eto-backups", "New World-")

        assertThat(first).isEqualTo(game.resolve("eto-backups/New World").toAbsolutePath())
        assertThat(second).isNotEqualTo(first)
        assertThat(first.startsWith(game.resolve("saves").toAbsolutePath())).isFalse()
        assertThat(PendingRollbacks.singlePlayerRepository(game, temp.resolve("store").toAbsolutePath().toString(), "New World"))
            .isEqualTo(temp.resolve("store/New World").toAbsolutePath())
        assertThat(PendingRollbacks.singlePlayerWorld("New World")).isEqualTo("saves/New World")
    }

    @Test
    fun `a dedicated server carries out the request it stopped for when it starts`() {
        val server = temp.resolve("server")
        val store = BackupStore(PendingRollbacks.serverRepository(server, "eto-backups"))
        val (then, _) = requested(server, store, "World")

        val outcome = PendingRollbacks.finishServer(server, "eto-backups")

        assertThat(outcome!!.ok()).isTrue()
        assertThat(content(server.resolve("World"))).isEqualTo(then)
        assertThat(PendingRollbacks.finishServer(server, "eto-backups")).isNull()
    }

    @Test
    fun `a swap that fails stops the start, and the next start finishes it`() {
        val server = temp.resolve("server")
        val store = BackupStore(PendingRollbacks.serverRepository(server, "eto-backups"))
        val (then, now) = requested(server, store, "World")
        killedBeforeTheSwap(store, "World")
        val obstacle = store.dir().resolve("rollback/old/in-the-way")
        obstacle.parent.createDirectories()
        obstacle.writeText("x")

        assertThatThrownBy { PendingRollbacks.finishServer(server, "eto-backups") }
            .isInstanceOf(PendingRollbacks.HalfReplaced::class.java)
            .hasMessageContaining("stopped halfway")
        assertThat(content(server.resolve("World"))).isEqualTo(now)

        obstacle.deleteExisting()
        obstacle.parent.deleteExisting()
        assertThat(PendingRollbacks.finishServer(server, "eto-backups")!!.ok()).isTrue()
        assertThat(content(server.resolve("World"))).isEqualTo(then)
    }

    @Test
    fun `a request whose snapshot cannot be restored lets the server start on the unchanged world`() {
        val server = temp.resolve("server")
        val store = BackupStore(PendingRollbacks.serverRepository(server, "eto-backups"))
        val (_, now) = requested(server, store, "World")
        Files.walk(store.dir().resolve("objects")).use { files -> files.filter { Files.isRegularFile(it) }.findFirst().get().deleteExisting() }

        val outcome = PendingRollbacks.finishServer(server, "eto-backups")

        assertThat(outcome!!.ok()).isFalse()
        assertThat(content(server.resolve("World"))).isEqualTo(now)
        assertThat(Rollback.pendingWorld(store)).isNull()
    }

    @Test
    fun `the game's start finishes the single-player rollbacks it was stopped in`() {
        val game = temp.resolve(".minecraft")
        val killedEarly = BackupStore(PendingRollbacks.singlePlayerRepository(game, "eto-backups", "First"))
        val (firstThen, _) = requested(game, killedEarly, "saves/First")
        val killedMidway = BackupStore(PendingRollbacks.singlePlayerRepository(game, "eto-backups", "Second"))
        val (secondThen, _) = requested(game, killedMidway, "saves/Second")
        killedBeforeTheSwap(killedMidway, "saves/Second")
        Files.move(game.resolve("saves/Second"), killedMidway.dir().resolve("rollback/old"))

        val failed = PendingRollbacks.finishSinglePlayer(game, "eto-backups")

        assertThat(failed).isEmpty()
        assertThat(content(game.resolve("saves/First"))).isEqualTo(firstThen)
        assertThat(content(game.resolve("saves/Second"))).isEqualTo(secondThen)
        assertThat(Rollback.pendingWorld(killedEarly)).isNull()
        assertThat(Rollback.pendingWorld(killedMidway)).isNull()
    }

    @Test
    fun `the game's start leaves alone a request for another world and reports one it cannot finish`() {
        val game = temp.resolve(".minecraft")
        val mismatched = BackupStore(PendingRollbacks.singlePlayerRepository(game, "eto-backups", "Named"))
        val (_, namedNow) = requested(game, mismatched, "saves/Other")
        val blocked = BackupStore(PendingRollbacks.singlePlayerRepository(game, "eto-backups", "Blocked"))
        val (_, blockedNow) = requested(game, blocked, "saves/Blocked")
        killedBeforeTheSwap(blocked, "saves/Blocked")
        blocked.dir().resolve("rollback/old/in-the-way").also { it.parent.createDirectories() }.writeText("x")

        val failed = PendingRollbacks.finishSinglePlayer(game, "eto-backups")

        assertThat(failed).containsExactly("Blocked")
        assertThat(Rollback.pendingWorld(mismatched)).isEqualTo("saves/Other")
        assertThat(content(game.resolve("saves/Other"))).isEqualTo(namedNow)
        assertThat(content(game.resolve("saves/Blocked"))).isEqualTo(blockedNow)
        assertThat(Rollback.pendingWorld(blocked)).isEqualTo("saves/Blocked")
    }

    @Test
    fun `without a repository directory there is nothing to finish`() {
        val game = temp.resolve(".minecraft")

        assertThat(PendingRollbacks.finishSinglePlayer(game, "eto-backups")).isEmpty()
        assertThat(PendingRollbacks.finishServer(game, "eto-backups")).isNull()
        assertThat(game.resolve("eto-backups").exists()).isFalse()
    }

    private companion object {
        const val TARGET = "20260101T000000Z"
        const val PRE = "20260102T000000Z"
    }
}
