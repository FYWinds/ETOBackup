package fyi.fyw.etobackup

import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.Random
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RollbackTest {
    @TempDir
    lateinit var temp: Path

    private lateinit var server: Path
    private lateinit var repository: Path
    private lateinit var store: BackupStore
    private val random = Random(11)

    /** The world as snapshot [TARGET] has it, the world as it is now, and what else the snapshot holds, as it is now. */
    private lateinit var then: Map<String, List<Byte>>
    private lateinit var now: Map<String, List<Byte>>
    private lateinit var claims: Map<String, List<Byte>>

    @BeforeEach
    fun tree() {
        server = temp.resolve("server")
        repository = server.resolve("eto-backups")
        store = BackupStore(repository)
        file("World/level.dat", 900)
        file("World/region/r.0.0.mca", 200 * 1024)
        file("World/DIM-1/region/r.0.0.mca", 70 * 1024)
        file("serverutilities/claims.json", 7_000)
        snapshot(TARGET, Manifest.AUTO)
        then = content("World")
        file("World/level.dat", 950)
        file("World/region/r.1.0.mca", 5_000)
        server.resolve("World/DIM-1/region/r.0.0.mca").deleteExisting()
        file("serverutilities/claims.json", 8_000)
        now = content("World")
        claims = content("serverutilities")
    }

    private fun file(path: String, size: Int) {
        val file = server.resolve(path)
        file.parent.createDirectories()
        file.writeBytes(ByteArray(size).also(random::nextBytes))
    }

    private fun snapshot(name: String, kind: String) {
        val manifest = Capture.run(server, listOf("World", "serverutilities"), emptyList(), null, name, store::put).manifest()
        manifest.kind = kind
        store.commit(manifest)
    }

    private fun content(directory: String): Map<String, List<Byte>> {
        val root = server.resolve(directory).toFile()
        return root.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList() }
    }

    private fun request() = Rollback.request(store, TARGET, PRE, "tester", "World")

    /** The state a run leaves that was killed after [moves] of the two moves. */
    private fun interrupted(moves: Int) {
        request()
        val work = repository.resolve("rollback")
        store.restore(store.read(TARGET), work.resolve("new"), "World/")
        val marker = repository.resolve("pending-rollback")
        marker.writeText(marker.readText().replace("step restore", "step swap"))
        if (moves >= 1) Files.move(server.resolve("World"), work.resolve("old"))
        if (moves >= 2) Files.move(work.resolve("new/World"), server.resolve("World"))
    }

    private fun assertRolledBack(outcome: Rollback.Outcome?) {
        assertThat(outcome!!.ok()).isTrue()
        assertThat(content("World")).isEqualTo(then)
        assertThat(content("eto-backups/rollback/old")).isEqualTo(now)
        assertThat(content("serverutilities")).isEqualTo(claims)
        assertThat(repository.resolve("pending-rollback").exists()).isFalse()
        assertThat(repository.resolve("rollback/new").exists()).isFalse()
        assertThat(Rollback.apply(server, store)).isNull()
    }

    @Test
    fun `the world becomes the snapshot, the rest of the server directory stays`() {
        request()

        assertRolledBack(Rollback.apply(server, store))

        val last = Rollback.last(store)
        assertThat(listOf(last.target(), last.pre(), last.by())).containsExactly(TARGET, PRE, "tester")
    }

    @Test
    fun `a run killed before the first move is finished by the next one`() {
        interrupted(0)

        assertRolledBack(Rollback.apply(server, store))
    }

    @Test
    fun `a run killed between the two moves is finished by the next one`() {
        interrupted(1)

        assertRolledBack(Rollback.apply(server, store))
    }

    @Test
    fun `a run killed after the two moves is finished by the next one`() {
        interrupted(2)

        assertRolledBack(Rollback.apply(server, store))
    }

    @Test
    fun `a snapshot that cannot be restored leaves the world alone and drops the request`() {
        repository.resolve("objects").listDirectoryEntries().first().listDirectoryEntries().first().deleteExisting()
        request()

        val outcome = Rollback.apply(server, store)

        assertThat(outcome.ok()).isFalse()
        assertThat(outcome.message()).contains("missing")
        assertThat(content("World")).isEqualTo(now)
        assertThat(repository.resolve("rollback/new").exists()).isFalse()
        assertThat(Rollback.apply(server, store)).isNull()
    }

    @Test
    fun `the server drops a request the start script did not carry out, unless the world is half replaced`() {
        request()

        assertThat(Rollback.dropUnapplied(store).ok()).isFalse()

        assertThat(Rollback.apply(server, store)).isNull()
        assertThat(content("World")).isEqualTo(now)
        interrupted(1)
        assertThatThrownBy { Rollback.dropUnapplied(store) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a snapshot taken before a rollback outlives the keep rules for the given days`() {
        snapshot("20260103T000000Z", Manifest.PRE_ROLLBACK)
        snapshot("20260109T000000Z", Manifest.PRE_ROLLBACK)
        snapshot("20260109T010000Z", Manifest.AUTO)
        snapshot("20260110T120000Z", Manifest.AUTO)

        val deleted = store.prune(Retention.Policy(0, 0, 0, 0, 0, 0), ZoneOffset.UTC, 7)

        assertThat(deleted).containsExactly(TARGET, "20260103T000000Z", "20260109T010000Z")
        assertThat(store.list()).containsExactly("20260109T000000Z", "20260110T120000Z")
    }

    private companion object {
        const val TARGET = "20260101T000000Z"
        const val PRE = "20260102T000000Z"
    }
}
