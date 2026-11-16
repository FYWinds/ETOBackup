package fyi.fyw.etobackup.api

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import net.minecraft.util.StatCollector

/**
 * Snapshot times and sizes as ETOBackup shows them: in this computer's time zone, in the game's language. Client
 * thread.
 */
object BackupText {
    private val THIS_YEAR = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val OTHER_YEAR = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** `2026-11-16`. */
    @JvmField
    val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** `17:44`. */
    @JvmField
    val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** `2026-11-16 17:44:05`. */
    @JvmField
    val FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** [time] in this computer's time zone. */
    @JvmStatic
    fun zoned(time: Instant): ZonedDateTime = time.atZone(ZoneId.systemDefault())

    /** `11-16 17:44`, with the year when it is not this year's. */
    @JvmStatic
    fun time(time: Instant): String {
        val zoned = zoned(time)
        return (if (zoned.year == zoned(Instant.now()).year) THIS_YEAR else OTHER_YEAR).format(zoned)
    }

    /** `25 min ago`. */
    @JvmStatic
    fun ago(time: Instant): String {
        val age = Duration.between(time, Instant.now())
        return when {
            age.toMinutes() < 1 -> StatCollector.translateToLocal("etobackup.ago.now")
            age.toHours() < 2 -> StatCollector.translateToLocalFormatted("etobackup.ago.minutes", age.toMinutes())
            age.toDays() < 2 -> StatCollector.translateToLocalFormatted("etobackup.ago.hours", age.toHours())
            else -> StatCollector.translateToLocalFormatted("etobackup.ago.days", age.toDays())
        }
    }

    /** `11-16 17:44 (25 min ago)` for a snapshot name, null for anything else. */
    @JvmStatic
    fun snapshot(name: String): String? = BackupView.snapshotTime(name)?.let { "${time(it)} (${ago(it)})" }

    /** `12.3 MB`. */
    @JvmStatic
    fun size(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1e6)

    /** `Automatic`, `On request` or `Before a rollback`. */
    @JvmStatic
    fun kind(kind: Kind): String = StatCollector.translateToLocal(
        when (kind) {
            Kind.AUTO -> "etobackup.kind.auto"
            Kind.MANUAL -> "etobackup.kind.manual"
            Kind.PRE_ROLLBACK -> "etobackup.kind.prerollback"
        },
    )
}
