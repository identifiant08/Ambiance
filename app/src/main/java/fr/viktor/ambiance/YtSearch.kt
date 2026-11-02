package fr.viktor.ambiance

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Un résultat affiché sur la TV : une playlist ou une vidéo longue. */
data class Hit(
    val type: String,      // "playlist" ou "video"
    val id: String,
    val title: String,
    val channel: String,
    val thumb: String,
    val meta: String,      // "24 vidéos" ou "10:00:00"
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type).put("id", id).put("title", title)
        .put("channel", channel).put("thumb", thumb).put("meta", meta)
}

/**
 * Recherche via l'API interne de YouTube (celle du site), sans compte ni clé.
 * Secours : la recherche de yt-dlp si YouTube change la structure de sa réponse.
 */
object YtSearch {

    private const val ENDPOINT = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false"
    private const val WEB_VERSION = "2.20260708.00.00"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

    // Filtres de la page de recherche YouTube
    private const val FILTER_PLAYLISTS = "EgIQAw=="
    private const val FILTER_LONG_VIDEOS = "EgQQARgC" // vidéos de plus de 20 min

    fun search(query: String, kind: String): List<Hit> {
        val hits = runCatching { searchWeb(query, kind) }.getOrDefault(emptyList())
        if (hits.isNotEmpty()) return hits
        // Secours yt-dlp (vidéos uniquement : yt-dlp ne sait pas chercher des playlists)
        return if (kind == "video") searchYtDlp(query) else hits
    }

    private fun searchWeb(query: String, kind: String): List<Hit> {
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB")
                .put("clientVersion", WEB_VERSION)
                .put("hl", "fr")
                .put("gl", "FR")))
            .put("query", query)
            .put("params", if (kind == "playlist") FILTER_PLAYLISTS else FILTER_LONG_VIDEOS)

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Origin", "https://www.youtube.com")
            setRequestProperty("Referer", "https://www.youtube.com/")
            setRequestProperty("X-Youtube-Client-Name", "1")
            setRequestProperty("X-Youtube-Client-Version", WEB_VERSION)
            setRequestProperty("User-Agent", UA)
        }
        val root = try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) error("YouTube a répondu $code")
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
        return if (kind == "playlist") parsePlaylists(root) else parseVideos(root)
    }

    // ---------- Vidéos ----------

    private fun parseVideos(root: JSONObject): List<Hit> {
        val out = mutableListOf<JSONObject>()
        collect(root, "videoRenderer", out)
        return out.mapNotNull { r ->
            val id = r.optString("videoId").ifBlank { return@mapNotNull null }
            val title = text(r.optJSONObject("title")).ifBlank { return@mapNotNull null }
            Hit(
                type = "video",
                id = id,
                title = title,
                channel = text(r.optJSONObject("ownerText")).ifBlank { text(r.optJSONObject("longBylineText")) },
                thumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                meta = text(r.optJSONObject("lengthText")),
            )
        }.filter { it.meta.isNotBlank() } // les directs n'ont pas de durée : on les écarte
            .distinctBy { it.id }
    }

    // ---------- Playlists (ancien et nouveau format de réponse) ----------

    private fun parsePlaylists(root: JSONObject): List<Hit> {
        val hits = mutableListOf<Hit>()

        val old = mutableListOf<JSONObject>()
        collect(root, "playlistRenderer", old)
        old.forEach { r ->
            val id = r.optString("playlistId")
            val title = text(r.optJSONObject("title"))
            if (id.isBlank() || title.isBlank()) return@forEach
            val thumb = r.optJSONArray("thumbnails")?.optJSONObject(0)?.optJSONArray("thumbnails")
                ?.let { it.optJSONObject(it.length() - 1)?.optString("url") }.orEmpty()
            val count = r.optString("videoCount")
            hits += Hit("playlist", id, title, text(r.optJSONObject("shortBylineText")), fixUrl(thumb),
                if (count.isNotBlank()) "$count vidéos" else "Playlist")
        }

        val lockups = mutableListOf<JSONObject>()
        collect(root, "lockupViewModel", lockups)
        lockups.forEach { r ->
            if (!r.optString("contentType").contains("PLAYLIST")) return@forEach
            val id = r.optString("contentId")
            val meta = r.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
            val title = meta?.optJSONObject("title")?.optString("content").orEmpty()
            if (id.isBlank() || title.isBlank()) return@forEach
            val channel = meta?.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")
                ?.optJSONArray("metadataRows")?.optJSONObject(0)?.optJSONArray("metadataParts")
                ?.optJSONObject(0)?.optJSONObject("text")?.optString("content").orEmpty()
            val image = r.optJSONObject("contentImage")
            val thumb = image?.let { firstString(it, "url") }.orEmpty()
            val badge = image?.let { node ->
                val badges = mutableListOf<JSONObject>()
                collect(node, "thumbnailBadgeViewModel", badges)
                badges.firstOrNull()?.optString("text")
            }.orEmpty()
            hits += Hit("playlist", id, title, channel, fixUrl(thumb), badge.ifBlank { "Playlist" })
        }

        // Les « mix » (RD…) sont infinis et générés à la volée : on les ignore
        return hits.filter { !it.id.startsWith("RD") }.distinctBy { it.id }
    }

    // ---------- Secours yt-dlp ----------

    private fun searchYtDlp(query: String): List<Hit> {
        runBlocking { App.ready.await() }.getOrThrow()
        val req = YoutubeDLRequest("ytsearch15:$query")
        req.addOption("--flat-playlist")
        req.addOption("-J")
        val root = JSONObject(App.ytdlp(req).out)
        val entries = root.optJSONArray("entries") ?: return emptyList()
        return (0 until entries.length()).mapNotNull { i ->
            val e = entries.optJSONObject(i) ?: return@mapNotNull null
            val id = e.optString("id").ifBlank { return@mapNotNull null }
            val dur = e.optDouble("duration", 0.0)
            if (dur in 0.1..1199.0) return@mapNotNull null // on garde les vidéos longues
            Hit("video", id, e.optString("title"), e.optString("channel", e.optString("uploader")),
                "https://i.ytimg.com/vi/$id/hqdefault.jpg", if (dur > 0) formatDuration(dur) else "")
        }
    }

    // ---------- Outils ----------

    /** Parcourt toute la réponse : insensible aux changements de structure autour des résultats. */
    private fun collect(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> node.keys().forEach { k ->
                val v = node.opt(k)
                if (k == key && v is JSONObject) out += v else collect(v, key, out)
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
        }
    }

    private fun firstString(node: Any?, key: String): String? {
        when (node) {
            is JSONObject -> {
                val v = node.opt(key)
                if (v is String && v.isNotBlank()) return v
                for (k in node.keys()) firstString(node.opt(k), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) firstString(node.opt(i), key)?.let { return it }
        }
        return null
    }

    private fun text(o: JSONObject?): String {
        if (o == null) return ""
        o.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        o.optString("content").takeIf { it.isNotBlank() }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return ""
        return buildString { for (i in 0 until runs.length()) append(runs.optJSONObject(i)?.optString("text").orEmpty()) }
    }

    private fun fixUrl(u: String) = if (u.startsWith("//")) "https:$u" else u

    private fun formatDuration(sec: Double): String {
        val s = sec.toLong()
        val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
    }
}
