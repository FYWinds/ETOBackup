package fyi.fyw.etobackup

import cpw.mods.fml.common.FMLCommonHandler
import cpw.mods.fml.common.Loader
import cpw.mods.fml.common.Mod
import cpw.mods.fml.common.StartupQuery
import cpw.mods.fml.common.event.FMLPreInitializationEvent
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent
import cpw.mods.fml.common.event.FMLServerStartingEvent
import cpw.mods.fml.common.event.FMLServerStoppedEvent
import fyi.fyw.etobackup.client.ClientSetup
import java.io.File
import java.nio.file.Path
import net.minecraft.util.StatCollector

@Mod(
    modid = ETOBackup.MOD_ID,
    name = "ETOBackup",
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*",
    modLanguageAdapter = "net.shadowfacts.forgelin.KotlinAdapter",
    dependencies = "required-after:forgelin",
    guiFactory = "fyi.fyw.etobackup.client.ConfigGuiFactory",
)
object ETOBackup {
    const val MOD_ID = "etobackup"

    /** The game directory: the server directory on a dedicated server. */
    private val gameDir: Path = Loader.instance().configDir.absoluteFile.parentFile.toPath().normalize()

    init {
        // Mod construction: before any mod is initialised and long before a world is loaded, so a rollback asked
        // for before the game stopped is carried out here.
        BackupConfig.load(File(Loader.instance().configDir, "$MOD_ID.cfg"))
        if (FMLCommonHandler.instance().side.isServer) {
            // Thrown when the world is left half replaced: the server must not start on it.
            PendingRollbacks.finishServer(gameDir, BackupConfig.directory)
        } else {
            val unfinished = PendingRollbacks.finishSinglePlayer(gameDir, BackupConfig.directory)
            if (unfinished.isNotEmpty()) LOG.error("The rollbacks of these worlds could not be finished; they cannot be opened until they are: {}", unfinished)
        }
    }

    @Mod.EventHandler
    fun preInit(event: FMLPreInitializationEvent) {
        BackupNet.register()
        BackupService.register()
        if (event.side.isClient) ClientSetup.register()
    }

    @Mod.EventHandler
    fun serverAboutToStart(event: FMLServerAboutToStartEvent) {
        val server = event.server
        if (server.isDedicatedServer) return
        // A single-player world whose rollback could not be finished when the game started is tried once more.
        val repository = PendingRollbacks.singlePlayerRepository(gameDir, BackupConfig.directory, server.folderName)
        try {
            PendingRollbacks.finish(gameDir, BackupStore(repository))
        } catch (e: PendingRollbacks.HalfReplaced) {
            LOG.error("{}", e.message, e)
            StartupQuery.notify(StatCollector.translateToLocalFormatted("etobackup.halfway", e.cause?.message ?: e.message))
            StartupQuery.abort()
        }
    }

    @Mod.EventHandler
    fun serverStarting(event: FMLServerStartingEvent) {
        event.registerServerCommand(BackupCommand)
        BackupService.start(event.server)
    }

    @Mod.EventHandler
    fun serverStopped(event: FMLServerStoppedEvent) {
        BackupService.stop()
    }
}
