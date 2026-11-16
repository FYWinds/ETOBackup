package fyi.fyw.etobackup.api

import java.time.Instant

/** Why a snapshot was taken. */
enum class Kind {
    /** By the timer. */
    AUTO,

    /** On request: `/etobackup now` or the Snapshot Now button. */
    MANUAL,

    /** The world just before a rollback replaced it; going back to it undoes that rollback. */
    PRE_ROLLBACK,
}

/** One snapshot of a repository, as listed in [State.snapshots]. */
class Snapshot(
    /** What the commands take: the UTC time it was taken, as in `20261116T154500Z`. */
    val name: String,
    /** When it was taken. */
    val time: Instant,
    val kind: Kind,
    /**
     * One line on why it was taken: for [Kind.MANUAL] the player who asked, for [Kind.PRE_ROLLBACK] the rollback's
     * target snapshot and the player who asked for it (separated by a space), empty for [Kind.AUTO].
     */
    val note: String,
    /** The number of files it holds. */
    val files: Long,
    /** The size of those files. */
    val bytes: Long,
    /** What it added to the repository, compressed: about the size of the change since the snapshot before it. */
    val storedBytes: Long,
) {
    /** The player who asked for it ([Kind.MANUAL]) or for the rollback it was taken before; empty for [Kind.AUTO]. */
    val by: String
        get() = when (kind) {
            Kind.MANUAL -> note
            Kind.PRE_ROLLBACK -> note.substringAfter(' ', "")
            Kind.AUTO -> ""
        }

    /** For [Kind.PRE_ROLLBACK], the snapshot that rollback went back to; null otherwise. */
    val rollbackTarget: String?
        get() = if (kind == Kind.PRE_ROLLBACK) note.substringBefore(' ') else null
}
