package dev.murmur.android

import android.content.Context
import java.io.File
import java.time.Instant

/** Only caller-controlled event names and exception classes, never content or paths. */
class TechnicalLog(context: Context) {
    private val folder = File(context.filesDir, "logs").apply { mkdirs() }
    @Synchronized fun event(name: String, error: Throwable? = null) {
        require(name.matches(Regex("[a-z0-9_.-]{1,80}")))
        runCatching {
        val file = File(folder, "murmur.log")
        if (file.length() > 1_000_000) {
            File(folder, "murmur.1.log").delete()
            file.renameTo(File(folder, "murmur.1.log"))
        }
        file.appendText("${Instant.now()} $name${error?.let { " ${it.javaClass.simpleName}" } ?: ""}\n")
        }
    }
}
