package fr.viktor.ambiance

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Une vidéo de la file d'attente. */
data class Entry(val id: String, val title: String)

/** Flux directs renvoyés par yt-dlp : vidéo seule + audio seul, ou un flux combiné. */
data class Streams(
    val video: String,
    val audio: String?,
    val videoHeaders: Map<String, String>,
    val audioHeaders: Map<String, String>,
    val title: String,
    val height: Int,
    val at: Long = System.currentTimeMillis(),
)

/** Tout ce qui passe par yt-dlp : contenu d'une playlist et adresses des flux. */
object Resolver {

    private val cache = ConcurrentHashMap<String, Streams>()

    // Les adresses YouTube expirent au bout de ~6 h : on les garde 4 h au maximum
    private const val TTL = 4 * 3600_000L

    private fun ensureReady() = runBlocking { App.ready.await() }.getOrThrow()

    /** Liste des vidéos d'une playlist, sans résoudre les flux (rapide). */
    fun playlist(listId: String): List<Entry> {
        ensureReady()
        val req = YoutubeDLRequest("https://www.youtube.com/playlist?list=$listId")
        req.addOption("--flat-playlist")
        req.addOption("--playlist-end", "300")
        req.addOption("-J")
        val root = JSONObject(App.ytdlp(req, 120).out)
        val entries = root.optJSONArray("entries") ?: return emptyList()
        return (0 until entries.length()).mapNotNull { i ->
            val e = entries.optJSONObject(i) ?: return@mapNotNull null
            val id = e.optString("id")
            val title = e.optString("title")
            // Vidéos supprimées ou privées : yt-dlp les liste quand même
            if (id.length != 11 || title == "[Deleted video]" || title == "[Private video]") null
            else Entry(id, title)
        }
    }

    fun invalidate(id: String) {
        cache.keys.filter { it.startsWith("$id@") }.forEach { cache.remove(it) }
    }

    /**
     * Choix du format : on vise la meilleure image sous le plafond choisi,
     * en privilégiant les codecs que le Mi Box décode en matériel.
     */
    private fun formatFor(maxHeight: Int): String {
        val h = "[height<=$maxHeight]"
        val audio = "ba[ext=m4a]/ba"
        return if (maxHeight > 1080) {
            "bv*$h[vcodec^=vp09]+$audio/bv*$h[vcodec^=av01]+$audio/bv*$h[vcodec^=avc1]+$audio/bv*$h+ba/b$h/b"
        } else {
            "bv*$h[vcodec^=avc1]+$audio/bv*$h[vcodec^=vp09]+$audio/bv*$h+ba/b$h/b"
        }
    }

    fun streams(id: String, maxHeight: Int, onStep: (String) -> Unit = {}): Streams {
        val key = "$id@$maxHeight"
        cache[key]?.let { if (System.currentTimeMillis() - it.at < TTL) return it }
        ensureReady()

        // 1er essai : client « android_vr », qui donne des flux directs sans avoir à
        // exécuter le code JavaScript de YouTube (très lent sur le processeur du Mi Box).
        // 2e essai : réglages par défaut de yt-dlp, plus lents mais plus complets.
        val attempts = listOf(
            "youtube:player_client=android_vr" to 60L,
            null to 150L,
        )
        var last: Exception? = null
        for ((i, attempt) in attempts.withIndex()) {
            if (i > 0) onStep("Méthode de secours, cela peut prendre une minute")
            try {
                val s = extract(id, maxHeight, attempt.first, attempt.second)
                cache[key] = s
                return s
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("aucun flux")
    }

    private fun extract(id: String, maxHeight: Int, extractorArgs: String?, timeout: Long): Streams {
        val req = YoutubeDLRequest("https://www.youtube.com/watch?v=$id")
        req.addOption("--no-playlist")
        if (extractorArgs != null) req.addOption("--extractor-args", extractorArgs)
        req.addOption("-f", formatFor(maxHeight))
        req.addOption("-J")
        val o = JSONObject(App.ytdlp(req, timeout).out)

        val title = o.optString("title")
        val requested = o.optJSONArray("requested_formats")
        return if (requested != null && requested.length() >= 2) {
            val v = requested.getJSONObject(0)
            val a = requested.getJSONObject(1)
            Streams(v.getString("url"), a.getString("url"), headers(v, o), headers(a, o), title, v.optInt("height"))
        } else {
            val url = o.optString("url").ifBlank { error("yt-dlp n'a renvoyé aucun flux") }
            Streams(url, null, headers(o, o), emptyMap(), title, o.optInt("height"))
        }
    }

    private fun headers(fmt: JSONObject, root: JSONObject): Map<String, String> {
        val h = fmt.optJSONObject("http_headers") ?: root.optJSONObject("http_headers") ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        h.keys().forEach { k -> out[k] = h.optString(k) }
        return out
    }
}
