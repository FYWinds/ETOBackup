package fyi.fyw.etobackup

import java.io.File
import net.minecraftforge.common.config.ConfigCategory
import net.minecraftforge.common.config.Configuration

/**
 * `config/etobackup.cfg`: the server's settings (also those of a single-player world) and this client's own. Read when
 * the mod is constructed, again on `/etobackup reload` and when the config screen saves it. The values are safe to
 * read from any thread.
 */
object BackupConfig {
    const val SERVER = "server"
    const val CLIENT = "client"
    private const val LANG = "etobackup.config."

    private var config: Configuration? = null

    @Volatile
    var enabled = true
        private set

    @Volatile
    var intervalMinutes = 15
        private set

    @Volatile
    var idleIntervalMinutes = 0
        private set

    @Volatile
    var directory = "eto-backups"
        private set

    @Volatile
    var extraPaths: List<String> = emptyList()
        private set

    @Volatile
    var exclude: List<String> = emptyList()
        private set

    @Volatile
    var retention = Retention.Policy(6, 48, 14, 8, 12, 0)
        private set

    @Volatile
    var keepPreRollbackDays = 7
        private set

    @Volatile
    var rollbackDelaySeconds = 30
        private set

    @Volatile
    var screenshots = true
        private set

    @Volatile
    var screenshotWidth = 1280
        private set

    @Volatile
    var pauseMenuButton = true
        private set

    /** Reads [file], writing it with the defaults and comments for whatever it lacks. */
    @Synchronized
    fun load(file: File) {
        config = Configuration(file)
        sync()
    }

    /** Reads the file again, for `/etobackup reload`. */
    @Synchronized
    fun reload() {
        val config = config ?: return
        config.load()
        sync()
    }

    /** Takes over what the config screen changed and saves it. */
    @Synchronized
    fun saveChanges() {
        val config = config ?: return
        config.save()
        sync()
    }

    /** The categories, for the config screen. */
    fun categories(): List<ConfigCategory> {
        val config = config ?: return emptyList()
        return listOf(config.getCategory(SERVER), config.getCategory(CLIENT))
    }

    private fun sync() {
        val config = config ?: return
        config.setCategoryComment(SERVER, "The server's backups. In single player these apply to the worlds you play.")
        config.setCategoryLanguageKey(SERVER, LANG + SERVER)
        config.setCategoryComment(CLIENT, "This computer's own settings.")
        config.setCategoryLanguageKey(CLIENT, LANG + CLIENT)
        enabled = config.getBoolean(
            "enabled", SERVER, true,
            "Take snapshots of the world on a timer. Off: only /etobackup now takes one.", LANG + "enabled",
        )
        intervalMinutes = config.getInt(
            "intervalMinutes", SERVER, 15, 1, 1440,
            "Minutes between snapshots while a player is or was online since the last one.", LANG + "intervalMinutes",
        )
        idleIntervalMinutes = config.getInt(
            "idleIntervalMinutes", SERVER, 0, 0, 10080,
            "Minutes between snapshots while no player has been online since the last one. 0: none are taken then.",
            LANG + "idleIntervalMinutes",
        )
        directory = config.get(
            SERVER, "directory", "eto-backups",
            "The repository, relative to the server directory or absolute. In single player each world gets its own " +
                "repository below it, named like the world's folder, and the path is relative to the game directory. " +
                "Takes effect when the server or world starts.",
        ).setLanguageKey(LANG + "directory").setRequiresWorldRestart(true).string.trim().ifEmpty { "eto-backups" }
        extraPaths = list(
            config.getStringList(
                "extraPaths", SERVER, emptyArray(),
                "Files and directories backed up besides the world, relative to the server directory (in single " +
                    "player, the game directory). Missing ones are skipped.",
                null, LANG + "extraPaths",
            ),
        )
        exclude = list(
            config.getStringList(
                "exclude", SERVER, emptyArray(),
                "Paths left out with everything below them, relative to the server directory (in single player, " +
                    "the game directory).",
                null, LANG + "exclude",
            ),
        )
        retention = Retention.Policy(
            config.getInt(
                "keepAllHours", SERVER, 6, 0, 8760,
                "Every snapshot of this many hours before the newest one is kept.", LANG + "keepAllHours",
            ),
            config.getInt(
                "keepHourly", SERVER, 48, 0, 100000,
                "The last snapshot of each hour is kept, for this many hours that have one.", LANG + "keepHourly",
            ),
            config.getInt(
                "keepDaily", SERVER, 14, 0, 100000,
                "The last snapshot of each day is kept, for this many days that have one.", LANG + "keepDaily",
            ),
            config.getInt(
                "keepWeekly", SERVER, 8, 0, 100000,
                "The last snapshot of each week is kept, for this many weeks that have one.", LANG + "keepWeekly",
            ),
            config.getInt(
                "keepMonthly", SERVER, 12, 0, 100000,
                "The last snapshot of each month is kept, for this many months that have one.", LANG + "keepMonthly",
            ),
            config.getInt(
                "maxAgeDays", SERVER, 0, 0, 36500,
                "Snapshots more than this many days older than the newest one are dropped, whatever the keep rules " +
                    "say. 0: no limit.",
                LANG + "maxAgeDays",
            ),
        )
        keepPreRollbackDays = config.getInt(
            "keepPreRollbackDays", SERVER, 7, 0, 3650,
            "The snapshot taken just before a rollback is kept for this many days whatever the keep rules say, so " +
                "that the rollback can be undone.",
            LANG + "keepPreRollbackDays",
        )
        rollbackDelaySeconds = config.getInt(
            "rollbackDelaySeconds", SERVER, 30, 5, 600,
            "Seconds between a rollback being announced and the server stopping for it (in single player: the " +
                "world closing) while other players are online. Alone, the player who asked waits 5 seconds.",
            LANG + "rollbackDelaySeconds",
        )
        screenshots = config.getBoolean(
            "screenshots", CLIENT, true,
            "Keep a picture of your screen for every snapshot taken while you play; the backup screen shows it. " +
                "Stored on this computer only.",
            LANG + "screenshots",
        )
        screenshotWidth = config.getInt(
            "screenshotWidth", CLIENT, 1280, 320, 3840, "Width of those pictures in pixels.", LANG + "screenshotWidth",
        )
        pauseMenuButton = config.getBoolean(
            "pauseMenuButton", CLIENT, true,
            "Show a Backups button in the pause menu. The key binding opens the backup screen either way.",
            LANG + "pauseMenuButton",
        )
        if (config.hasChanged()) config.save()
    }

    private fun list(values: Array<String>): List<String> = values.map { it.trim() }.filter { it.isNotEmpty() }
}
