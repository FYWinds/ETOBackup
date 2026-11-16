package fyi.fyw.etobackup.api

import fyi.fyw.etobackup.client.BackupClient
import fyi.fyw.etobackup.client.BackupScreen
import java.time.Instant
import java.util.function.Consumer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiScreen

/**
 * The backups of the server this client is connected to (in single player: of the player's own world), for a screen
 * that shows them. ETOBackup's own screen ([open]) is built on this alone.
 *
 * Client only; every member is for the client thread. The server needs ETOBackup, the client too; a server without
 * it leaves [available] false.
 */
object BackupView {
    /** The current connection's server (or the player's own world) takes backups with ETOBackup. */
    @JvmStatic
    val available: Boolean
        get() = BackupClient.available

    /** The list the server sent last, or null before the first one; [refresh] asks for a new one. */
    @JvmStatic
    val state: State?
        get() = BackupClient.state

    /** When [state] arrived, in [System.currentTimeMillis]; 0 before the first one. */
    @JvmStatic
    val stateTime: Long
        get() = BackupClient.stateTime

    /** The server's last backup chat line, as plain text in this client's language; empty before the first one. */
    @JvmStatic
    val notice: String
        get() = BackupClient.notice

    /** A rollback countdown runs. A settings screen may want to open on the backups then, where it can be cancelled. */
    @JvmStatic
    val rollbackAnnounced: Boolean
        get() = BackupClient.rollbackAnnounced()

    /** Asks the server for a new [state]. */
    @JvmStatic
    fun refresh() = BackupClient.requestList()

    /** Asks for a snapshot now. Needs [State.canManage]; the answer is a chat line ([notice]). */
    @JvmStatic
    fun snapshotNow() = BackupClient.act(BackupClient.ACTION_NOW)

    /**
     * Asks for the world to go back to the snapshot [name], after the server's countdown. Needs [State.canManage]
     * and [State.rollbackReady]. Ask the player to confirm first: everything since that snapshot is undone (it can be
     * undone in turn, see [undo]).
     */
    @JvmStatic
    fun rollback(name: String) = BackupClient.act(BackupClient.ACTION_ROLLBACK, name)

    /** Asks for the world to go back to how it was just before the last rollback ([LastRollback.pre]). */
    @JvmStatic
    fun undo() = BackupClient.act(BackupClient.ACTION_UNDO)

    /** Stops the rollback countdown ([State.pending]). */
    @JvmStatic
    fun cancel() = BackupClient.act(BackupClient.ACTION_CANCEL)

    /**
     * Calls [listener] on the client thread whenever [available], [state] or [notice] changed, and once a second (for
     * countdowns and "minutes ago"). Close the result to stop.
     */
    @JvmStatic
    fun listen(listener: Runnable): AutoCloseable = BackupClient.listen(listener)

    /**
     * Loads this client's picture of the snapshot [snapshot] of the current repository off the client thread,
     * shrunk to about [maxWidth] pixels wide. [then] gets it on the client thread, or null when there is none.
     */
    @JvmStatic
    fun loadPicture(snapshot: String, maxWidth: Int, then: Consumer<Picture?>) =
        BackupClient.loadPicture(snapshot, maxWidth) { then.accept(it) }

    /** The time a snapshot name stands for, or null when [name] is not a snapshot name. */
    @JvmStatic
    fun snapshotTime(name: String): Instant? = BackupClient.snapshotTime(name)

    /** Opens ETOBackup's own screen; closing it returns to [parent]. */
    @JvmStatic
    fun open(parent: GuiScreen?) = Minecraft.getMinecraft().displayGuiScreen(BackupScreen(parent))
}
