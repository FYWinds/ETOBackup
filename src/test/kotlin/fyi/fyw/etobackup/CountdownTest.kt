package fyi.fyw.etobackup

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CountdownTest {
    /** The seconds left at which the chat says a countdown of [seconds] again after announcing it. */
    private fun reminders(seconds: Int): List<Int> =
        (seconds * 20 - 1 downTo 1).filter { BackupService.remindsAt(it) }.map { it / 20 }

    @Test
    fun `a countdown is said again only at 10 and 5 seconds left, when it started above them`() {
        assertThat(reminders(30)).containsExactly(10, 5)
        assertThat(reminders(10)).containsExactly(5)
        assertThat(reminders(5)).isEmpty()
    }

    @Test
    fun `the kick message names how old the snapshot is, with words for under a minute and one minute`() {
        assertThat(BackupService.kickWording(Duration.ofSeconds(40))).isEqualTo("etobackup.kick.now" to 0L)
        assertThat(BackupService.kickWording(Duration.ofSeconds(90))).isEqualTo("etobackup.kick.minute" to 1L)
        assertThat(BackupService.kickWording(Duration.ofMinutes(25))).isEqualTo("etobackup.kick.minutes" to 25L)
        assertThat(BackupService.kickWording(Duration.ofHours(5))).isEqualTo("etobackup.kick.hours" to 5L)
        assertThat(BackupService.kickWording(Duration.ofDays(3))).isEqualTo("etobackup.kick.days" to 3L)
        assertThat(Lang.english("etobackup.kick.now", "Alex", "0"))
            .isEqualTo("Alex is putting the world back to how it was less than a minute ago. The server is restarting; join again in about a minute.")
    }
}
