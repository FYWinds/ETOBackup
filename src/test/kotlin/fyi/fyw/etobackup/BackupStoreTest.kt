package fyi.fyw.etobackup

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.ZoneOffset
import java.util.Random
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BackupStoreTest {
    @TempDir
    lateinit var temp: Path

    private lateinit var server: Path
    private lateinit var store: BackupStore
    private lateinit var region: Path
    private val random = Random(7)
    private val old = FileTime.fromMillis(System.currentTimeMillis() - 3_600_000)

    @BeforeEach
    fun tree() {
        server = temp.resolve("server")
        store = BackupStore(temp.resolve("repository"))
        region = file("World/region/r.0.0.mca", 300 * 1024)
        file("World/level.dat", 900)
        file("World/empty.dat", 0)
        file("World/scratch/big.tmp", 5000)
        file("serverutilities/claims.json", 7_000)
    }

    /** A file last written an hour ago, as files untouched by the save before a capture are. */
    private fun file(path: String, size: Int): Path {
        val file = server.resolve(path)
        file.parent.createDirectories()
        file.writeBytes(ByteArray(size).also(random::nextBytes))
        Files.setLastModifiedTime(file, old)
        return file
    }

    private fun snapshot(name: String, previous: Manifest?): Capture.Result {
        val result = Capture.run(server, listOf("World", "serverutilities", "missing"), listOf("World/scratch"), previous, name, store::put)
        store.commit(result.manifest())
        return result
    }

    private fun content(root: Path): Map<String, List<Byte>> =
        root.toFile().walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(root.toFile()).invariantSeparatorsPath to it.readBytes().toList() }

    private fun restored(name: String): Map<String, List<Byte>> {
        val dest = temp.resolve("restored-$name")
        store.restore(store.read(name), dest)
        return content(dest)
    }

    private fun objects(): List<Path> =
        temp.resolve("repository/objects").listDirectoryEntries().flatMap { it.listDirectoryEntries() }

    @Test
    fun `a second snapshot stores only the slice that changed and both restore byte for byte`() {
        val first = snapshot("20260101T000000Z", null)
        val before = content(server) - "World/scratch/big.tmp"
        // 300 KiB is five slices, the three small files one each; the empty file has none.
        assertThat(first.newSlices()).isEqualTo(7)
        assertThat(first.manifest().files).doesNotContainKey("World/scratch/big.tmp")

        val bytes = region.readBytes()
        bytes[70_000] = (bytes[70_000] + 1).toByte()
        region.writeBytes(bytes)
        Files.setLastModifiedTime(region, FileTime.fromMillis(old.toMillis() + 60_000))
        val second = snapshot("20260101T001500Z", first.manifest())
        val after = content(server) - "World/scratch/big.tmp"

        assertThat(second.filesRead()).isEqualTo(1)
        assertThat(second.newSlices()).isEqualTo(1)
        assertThat(store.summary("20260101T001500Z").storedObjects()).isEqualTo(1)
        assertThat(store.list()).containsExactly("20260101T000000Z", "20260101T001500Z")
        assertThat(restored("20260101T000000Z")).isEqualTo(before)
        assertThat(restored("20260101T001500Z")).isEqualTo(after)
    }

    @Test
    fun `a file written just before a capture is read again even if its size and time stay the same`() {
        val fresh = server.resolve("World/level.dat")
        fresh.writeBytes(ByteArray(900) { 1 })
        val first = snapshot("20260101T000000Z", null)
        val time = Files.getLastModifiedTime(fresh)
        fresh.writeBytes(ByteArray(900) { 2 })
        Files.setLastModifiedTime(fresh, time)

        snapshot("20260101T001500Z", first.manifest())

        assertThat(restored("20260101T001500Z")["World/level.dat"]).isEqualTo(List(900) { 2.toByte() })
    }

    @Test
    fun `a change written through a handle that stays open is picked up`() {
        // The server keeps region files open; Windows then leaves the directory listing's size and time stale.
        RandomAccessFile(region.toFile(), "rw").use { open ->
            val first = snapshot("20260101T000000Z", null)
            open.seek(70_000)
            open.write(byteArrayOf(1, 2, 3))

            snapshot("20260101T001500Z", first.manifest())

            assertThat(restored("20260101T001500Z")["World/region/r.0.0.mca"]).isEqualTo(region.readBytes().toList())
        }
    }

    @Test
    fun `a file changed without its size or time changing is found by the rotating recheck`() {
        var manifest = snapshot("20260101T000000Z", null).manifest()
        val level = server.resolve("World/level.dat")
        level.writeBytes(ByteArray(900) { 5 })
        Files.setLastModifiedTime(level, old)
        val start = Instant.parse("2026-01-01T00:00:00Z")

        val found = (1..4 * Capture.RECHECK_ONE_IN).firstOrNull { run ->
            val result = snapshot(Manifest.nameOf(start.plusSeconds(900L * run)), manifest)
            manifest = result.manifest()
            "World/level.dat" in result.drifted()
        }

        assertThat(found).isNotNull()
        assertThat(restored(manifest.name)["World/level.dat"]).isEqualTo(List(900) { 5.toByte() })
    }

    @Test
    fun `a damaged or missing object is reported and stops a restore`() {
        val manifest = snapshot("20260101T000000Z", null).manifest()
        val files = objects()
        files[0].writeBytes(ByteArray(40) { 9 })
        Files.delete(files[1])

        assertThat(store.verify(manifest).problems()).hasSize(2)
        assertThatThrownBy { store.restore(manifest, temp.resolve("out")) }.isInstanceOf(IOException::class.java)
    }

    @Test
    fun `dropping a snapshot frees only the objects no other snapshot names`() {
        val first = snapshot("20260101T000000Z", null)
        region.writeBytes(ByteArray(300 * 1024).also(random::nextBytes))
        Files.setLastModifiedTime(region, FileTime.fromMillis(old.toMillis() + 60_000))
        snapshot("20260101T001500Z", first.manifest())
        val after = content(server) - "World/scratch/big.tmp"
        // Left behind by a run that died: an object nothing names and a half-written one.
        store.put("ab" + "0".repeat(62), ByteArray(10))
        store.abandon()
        temp.resolve("repository/objects/ab/dead.tmp").writeBytes(ByteArray(3))

        val deleted = store.prune(Retention.Policy(0, 0, 0, 0, 0, 0), ZoneOffset.UTC, 7)
        val freed = store.collectGarbage()

        assertThat(deleted).containsExactly("20260101T000000Z")
        // The first snapshot's five region slices, the orphan and the temporary file.
        assertThat(freed[0]).isEqualTo(7)
        assertThat(objects()).hasSize(7)
        assertThat(restored("20260101T001500Z")).isEqualTo(after)
    }

    @Test
    fun `a snapshot that cannot be read stops garbage collection before anything is deleted`() {
        snapshot("20260101T000000Z", null)
        store.put("ab" + "0".repeat(62), ByteArray(10))
        store.abandon()
        temp.resolve("repository/snapshots/20260101T001500Z.txt.zst").writeBytes(ByteArray(50) { 3 })
        val before = objects().size

        assertThatThrownBy { store.collectGarbage() }.isInstanceOf(IOException::class.java)

        assertThat(objects()).hasSize(before)
        assertThat(temp.resolve("repository/objects/ab").exists()).isTrue()
    }
}
