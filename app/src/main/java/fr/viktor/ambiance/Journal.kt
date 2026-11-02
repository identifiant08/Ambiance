package fr.viktor.ambiance

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Journal des dernières étapes de lecture, consultable sur la TV (Réglages) pour diagnostiquer. */
object Journal {
    private const val MAX = 80
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

    @Synchronized
    fun add(msg: String) {
        Log.i("Ambiance", msg)
        lines.addLast("${fmt.format(Date())}  $msg")
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun text(): String = lines.reversed().joinToString("\n")

    @Synchronized
    fun last(): String = lines.lastOrNull()?.substringAfter("  ").orEmpty()
}
