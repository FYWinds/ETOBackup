package fyi.fyw.etobackup

import cpw.mods.fml.relauncher.FMLInjectionData
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BackupConfigTest {
    @TempDir
    lateinit var temp: Path

    private lateinit var file: Path

    @BeforeEach
    fun gameDirectory() {
        // Forge's Configuration asks FML for the game directory, which the launcher sets.
        FMLInjectionData::class.java.getDeclaredField("minecraftHome").apply { isAccessible = true }.set(null, temp.toFile())
        file = temp.resolve("config/etobackup.cfg")
    }

    @Test
    fun `a missing file gets the defaults written to it`() {
        BackupConfig.load(file.toFile())

        assertThat(BackupConfig.enabled).isTrue()
        assertThat(BackupConfig.intervalMinutes).isEqualTo(15)
        assertThat(BackupConfig.idleIntervalMinutes).isZero()
        assertThat(BackupConfig.directory).isEqualTo("eto-backups")
        assertThat(BackupConfig.extraPaths).isEmpty()
        assertThat(BackupConfig.exclude).isEmpty()
        val retention = BackupConfig.retention
        assertThat(listOf(retention.allHours(), retention.hourly(), retention.daily(), retention.weekly(), retention.monthly(), retention.maxAgeDays()))
            .containsExactly(6, 48, 14, 8, 12, 0)
        assertThat(BackupConfig.keepPreRollbackDays).isEqualTo(7)
        assertThat(BackupConfig.rollbackDelaySeconds).isEqualTo(30)
        assertThat(BackupConfig.screenshots).isTrue()
        assertThat(BackupConfig.screenshotWidth).isEqualTo(1280)
        assertThat(BackupConfig.pauseMenuButton).isTrue()
        val written = file.readText()
        assertThat(written).contains("B:enabled=true", "S:directory=eto-backups", "I:intervalMinutes=15", "B:pauseMenuButton=true")
    }

    @Test
    fun `values are read from the file, out of range numbers are clamped and blank list entries dropped`() {
        file.parent.toFile().mkdirs()
        file.writeText(
            """
            server {
                B:enabled=false
                I:intervalMinutes=0
                I:idleIntervalMinutes=120
                S:directory=D:/backups/world
                S:extraPaths <
                    serverutilities
                    
                    journeymap/data 
                 >
                S:exclude <
                    serverutilities/temp
                 >
                I:keepAllHours=2
                I:keepDaily=99999999
                I:rollbackDelaySeconds=1
            }

            client {
                B:screenshots=false
                I:screenshotWidth=640
                B:pauseMenuButton=false
            }
            """.trimIndent(),
        )

        BackupConfig.load(file.toFile())

        assertThat(BackupConfig.enabled).isFalse()
        assertThat(BackupConfig.intervalMinutes).isEqualTo(1)
        assertThat(BackupConfig.idleIntervalMinutes).isEqualTo(120)
        assertThat(BackupConfig.directory).isEqualTo("D:/backups/world")
        assertThat(BackupConfig.extraPaths).containsExactly("serverutilities", "journeymap/data")
        assertThat(BackupConfig.exclude).containsExactly("serverutilities/temp")
        assertThat(BackupConfig.retention.allHours()).isEqualTo(2)
        assertThat(BackupConfig.retention.daily()).isEqualTo(100000)
        assertThat(BackupConfig.retention.hourly()).isEqualTo(48)
        assertThat(BackupConfig.rollbackDelaySeconds).isEqualTo(5)
        assertThat(BackupConfig.screenshots).isFalse()
        assertThat(BackupConfig.screenshotWidth).isEqualTo(640)
        assertThat(BackupConfig.pauseMenuButton).isFalse()
    }

    @Test
    fun `reload reads the file again`() {
        BackupConfig.load(file.toFile())
        file.writeText(file.readText().replace("I:intervalMinutes=15", "I:intervalMinutes=45").replace("B:enabled=true", "B:enabled=false"))

        BackupConfig.reload()

        assertThat(BackupConfig.intervalMinutes).isEqualTo(45)
        assertThat(BackupConfig.enabled).isFalse()
    }
}
