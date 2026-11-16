package fyi.fyw.etobackup

import java.nio.charset.StandardCharsets
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LangTest {
    private fun read(locale: String): Map<String, String> {
        val stream = javaClass.getResourceAsStream("/assets/etobackup/lang/$locale.lang")!!
        return stream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
        }
    }

    private fun placeholders(text: String): Int = Regex("%s").findAll(text).count()

    @Test
    fun `both languages have the same keys with the same placeholders`() {
        val english = read("en_US")
        val chinese = read("zh_CN")

        assertThat(chinese.keys).containsExactlyInAnyOrderElementsOf(english.keys)
        for ((key, text) in english) {
            assertThat(placeholders(chinese.getValue(key))).describedAs(key).isEqualTo(placeholders(text))
        }
    }

    @Test
    fun `players without the mod get the English text`() {
        assertThat(Lang.english("etobackup.msg.cancelled", "Alex")).isEqualTo("Alex cancelled the rollback.")
        assertThat(Lang.english("etobackup.msg.unknown")).isEqualTo("etobackup.msg.unknown")
        assertThat(BackupChat.text("etobackup.msg.no_snapshot", "20260101T120000Z"))
            .isEqualTo("There is no snapshot 2026-01-01 12:00 UTC (20260101T120000Z).")
    }
}
