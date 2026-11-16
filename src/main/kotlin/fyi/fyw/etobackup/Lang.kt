package fyi.fyw.etobackup

import java.nio.charset.StandardCharsets
import java.util.IllegalFormatException
import java.util.Locale

/**
 * ETOBackup's English texts, read from its own `en_US.lang`: what players without the mod, the console and the log
 * get. The game's translator is not used for these because a single-player world's server shares it with the client,
 * whose language may be another.
 */
internal object Lang {
    private val english: Map<String, String> by lazy {
        val stream = Lang::class.java.getResourceAsStream("/assets/etobackup/lang/en_US.lang")
            ?: return@lazy emptyMap<String, String>()
        stream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") && '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
        }
    }

    /** The English text of [key] with [args], or [key] itself when there is none. */
    fun english(key: String, vararg args: Any?): String {
        val text = english[key] ?: return key
        return try {
            String.format(Locale.ROOT, text, *args)
        } catch (e: IllegalFormatException) {
            text
        }
    }
}
