package fyi.fyw.etobackup.api

import fyi.fyw.etobackup.BackupService
import java.time.Instant

/**
 * The backups of the server that runs in this game (a dedicated server, or the integrated one of a single-player
 * world), for a status display. Any thread; both are null while no server runs.
 */
object BackupStatus {
    /** When the newest snapshot was taken, or null without one. */
    @JvmStatic
    val lastSnapshot: Instant?
        get() = BackupService.lastSnapshot

    /** The size of the repository, or null while it has not been measured yet (it is after each snapshot). */
    @JvmStatic
    val usage: Usage?
        get() = BackupService.usage
}
