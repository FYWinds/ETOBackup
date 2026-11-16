package fyi.fyw.etobackup.api

import java.time.Instant

/**
 * What the server (in single player: the player's own world) last told this client about its backups. Received on
 * the client thread; never changed afterwards, a newer list replaces the whole object ([BackupView.state]).
 */
class State(
    /** The repository's id. Pictures are kept per repository, so a different server's snapshots never get them. */
    val repository: String,
    /**
     * The world is a single-player world (an integrated server, also when opened to LAN): a rollback closes the world,
     * puts it back and opens it again instead of restarting a server.
     */
    val singlePlayer: Boolean,
    /** This player may take snapshots, roll back, undo and cancel (OP level 2, or the owner of a single-player world). */
    val canManage: Boolean,
    /**
     * A rollback can be carried out: always in single player; on a dedicated server only when its start script runs
     * `BackupCli apply-pending`. Without it the server refuses rollbacks.
     */
    val rollbackReady: Boolean,
    /** The rollback that is counting down or being carried out, or null. */
    val pending: Pending?,
    /** How the last rollback ended, or null when there never was one. */
    val last: LastRollback?,
    /** Oldest first. */
    val snapshots: List<Snapshot>,
)

/** An announced rollback. */
class Pending(
    /** The snapshot the world goes back to. */
    val target: String,
    /** The player who asked for it. */
    val by: String,
    /**
     * Seconds the countdown had left when the [State] was sent; 0 when it is over and the server is stopping for the
     * rollback (in single player: the world is being closed).
     */
    val seconds: Int,
    /** When the countdown ends, in [System.currentTimeMillis] of this computer. */
    val endsAt: Long,
) {
    /** Seconds the countdown has left now; 0 once it is over. */
    fun secondsLeft(): Int = maxOf(0L, (endsAt - System.currentTimeMillis() + 999) / 1000).toInt()
}

/** How the last rollback ended. */
class LastRollback(
    /** The snapshot the world was to go back to. */
    val target: String,
    /** The snapshot taken just before; going back to it ([BackupView.undo]) undoes the rollback. */
    val pre: String,
    /** The player who asked for it. */
    val by: String,
    /** When it was carried out or given up. */
    val time: Instant,
    /** The world was put back. False: it was left as it was, see [message]. */
    val ok: Boolean,
    /** Why it failed, in English, or empty. */
    val message: String,
)

/** A picture this client kept of its screen when a snapshot was taken: [rgba] holds [width] x [height] pixels, top row first. */
class Picture(val width: Int, val height: Int, val rgba: ByteArray)

/** How much room a repository takes. */
class Usage(
    /** The number of snapshots. */
    val snapshots: Long,
    /** The bytes the snapshots and their objects take on disk. */
    val bytes: Long,
)
