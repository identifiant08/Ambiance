package fr.viktor.ambiance

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Accès direct à l'API interne de YouTube, sans passer par yt-dlp.
 * Python est très lent sur le processeur du Mi Box (plusieurs minutes par vidéo) ;
 * ces requêtes répondent en moins d'une seconde. yt-dlp reste le secours.
 *
 * Les profils de clients sont repris de yt-dlp (extractor/youtube/_base.py) : ceux-ci
 * donnent des adresses de flux directes, sans code JavaScript à exécuter.
 */
object NativeYt {

    private const val WEB_VERSION = "2.20260708.00.00"
    private const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

    private class Client(val number: Int, val ctx: JSONObject) {
        val ua: String get() = ctx.getString("userAgent")
        val version: String get() = ctx.getString("clientVersion")
    }

    private val CLIENTS = listOf(
        Client(101, JSONObject()
            .put("clientName", "VISIONOS")
            .put("clientVersion", "1.02")
            .put("deviceMake", "Apple")
            .put("deviceModel", "RealityDevice17,1")
            .put("userAgent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15")
            .put("osName", "visionOS")
            .put("osVersion", "26.5.23O471")),
        Client(28, JSONObject()
            .put("clientName", "ANDROID_VR")
            .put("clientVersion", "1.65.10")
            .put("deviceMake", "Oculus")
            .put("deviceModel", "Quest 3")
            .put("androidSdkVersion", 32)
            .put("userAgent", "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip")
            .put("osName", "Android")
            .put("osVersion", "12L")),
    )

    // ---------- Playlist ----------

    /** Vidéos d'une playlist (jusqu'à ~300), via la même requête que le site. */
    fun playlist(listId: String): List<Entry> {
        val out = LinkedHashMap<String, String>()
        var root = post("browse", JSONObject().put("context", webContext()).put("browseId", "VL$listId"), webHeaders())
        repeat(3) {
            collectVideos(root, out)
            val token = continuation(root) ?: return out.map { Entry(it.key, it.value) }
            root = post("browse", JSONObject().put("context", webContext()).put("continuation", token), webHeaders())
        }
        collectVideos(root, out)
        return out.map { Entry(it.key, it.value) }
    }

    private fun collectVideos(root: JSONObject, out: MutableMap<String, String>) {
        // Ancien format
        val old = mutableListOf<JSONObject>()
        collect(root, "playlistVideoRenderer", old)
        old.forEach { r ->
            val id = r.optString("videoId")
            if (id.length == 11 && r.optBoolean("isPlayable", true)) out.putIfAbsent(id, text(r.optJSONObject("title")))
        }
        // Nouveau format (2025+)
        val lockups = mutableListOf<JSONObject>()
        collect(root, "lockupViewModel", lockups)
        lockups.forEach { r ->
            if (!r.optString("contentType").contains("VIDEO")) return@forEach
            val id = r.optString("contentId")
            val title = r.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                ?.optJSONObject("title")?.optString("content").orEmpty()
            if (id.length == 11) out.putIfAbsent(id, title)
        }
    }

    private fun continuation(root: JSONObject): String? {
        val cmds = mutableListOf<JSONObject>()
        collect(root, "continuationCommand", cmds)
        return cmds.firstOrNull()?.optString("token")?.takeIf { it.isNotBlank() }
    }

    // ---------- Flux d'une vidéo ----------

    fun streams(id: String, maxHeight: Int): Streams {
        var lastError = "aucun flux"
        for (c in CLIENTS) {
            try {
                val body = JSONObject()
                    .put("context", JSONObject().put("client", JSONObject(c.ctx.toString()).put("hl", "fr").put("gl", "FR")))
                    .put("videoId", id)
                    .put("contentCheckOk", true)
                    .put("racyCheckOk", true)
                val r = post("player", body, mapOf(
                    "User-Agent" to c.ua,
                    "X-Youtube-Client-Name" to c.number.toString(),
                    "X-Youtube-Client-Version" to c.version,
                    "Origin" to "https://www.youtube.com",
                ))
                val ps = r.optJSONObject("playabilityStatus")
                val name = c.ctx.optString("clientName")
                if (ps?.optString("status") != "OK") {
                    lastError = ps?.optString("reason")?.ifBlank { null } ?: ps?.optString("status") ?: "lecture refusée"
                    Journal.add("$name refusé : ${ps?.optString("status")} $lastError")
                    continue
                }
                pick(r, c, maxHeight)?.let {
                    Journal.add("$name OK : ${if (it.hls) "HLS" else "${it.height}p"}${if (it.audio != null) " + audio séparé" else ""}" +
                        (if (it.video.contains("&n=") || it.video.contains("?n=")) ", paramètre n présent" else ""))
                    return it
                }
                lastError = "pas de flux direct"
                Journal.add("$name : réponse sans flux direct")
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                Journal.add("${c.ctx.optString("clientName")} erreur : $lastError")
            }
        }
        error(lastError)
    }

    private fun pick(r: JSONObject, c: Client, maxHeight: Int): Streams? {
        val sd = r.optJSONObject("streamingData") ?: return null
        val title = r.optJSONObject("videoDetails")?.optString("title").orEmpty()
        val headers = mapOf("User-Agent" to c.ua)

        val adaptive = arr(sd.optJSONArray("adaptiveFormats")).filter { it.optString("url").isNotBlank() && !it.has("drmFamilies") }
        val videos = adaptive.filter { it.optString("mimeType").startsWith("video/") && it.optInt("height") in 1..maxHeight }
        val audios = adaptive.filter { it.optString("mimeType").startsWith("audio/mp4") }
            .ifEmpty { adaptive.filter { it.optString("mimeType").startsWith("audio/") } }

        // Le Mi Box décode l'avc1 (H.264) et le VP9 en matériel ; on évite l'AV1
        fun codecRank(f: JSONObject): Int {
            val m = f.optString("mimeType")
            return when {
                maxHeight <= 1080 && m.contains("avc1") -> 3
                m.contains("vp9") || m.contains("vp09") -> 2
                m.contains("avc1") -> 1
                else -> 0
            }
        }
        val video = videos.filter { codecRank(it) > 0 }
            .maxWithOrNull(compareBy<JSONObject>({ it.optInt("height") }, { codecRank(it) }, { it.optInt("fps") }, { it.optInt("bitrate") }))
        val audio = audios.maxByOrNull { it.optInt("bitrate") }

        if (video != null && audio != null) {
            Journal.add("Formats : vidéo ${video.optInt("itag")} ${video.optString("mimeType").substringBefore(';')} ${video.optInt("height")}p, audio ${audio.optInt("itag")}")
            return Streams(video.getString("url"), audio.getString("url"), headers, headers, title, video.optInt("height"))
        }
        // Flux HLS : ExoPlayer choisit lui-même la qualité
        sd.optString("hlsManifestUrl").takeIf { it.isNotBlank() }?.let {
            return Streams(it, null, headers, emptyMap(), title, 0, hls = true)
        }
        // Dernier recours : flux combiné (souvent 360p)
        val muxed = arr(sd.optJSONArray("formats")).filter { it.optString("url").isNotBlank() }.maxByOrNull { it.optInt("height") }
            ?: return null
        return Streams(muxed.getString("url"), null, headers, emptyMap(), title, muxed.optInt("height"))
    }

    // ---------- Outils ----------

    private fun webContext() = JSONObject().put("client", JSONObject()
        .put("clientName", "WEB").put("clientVersion", WEB_VERSION).put("hl", "fr").put("gl", "FR"))

    private fun webHeaders() = mapOf(
        "User-Agent" to WEB_UA,
        "X-Youtube-Client-Name" to "1",
        "X-Youtube-Client-Version" to WEB_VERSION,
        "Origin" to "https://www.youtube.com",
        "Referer" to "https://www.youtube.com/",
    )

    private fun post(endpoint: String, body: JSONObject, headers: Map<String, String>): JSONObject {
        val conn = (URL("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) error("YouTube a répondu $code")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun arr(a: JSONArray?): List<JSONObject> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private fun collect(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> node.keys().forEach { k ->
                val v = node.opt(k)
                if (k == key && v is JSONObject) out += v else collect(v, key, out)
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
        }
    }

    private fun text(o: JSONObject?): String {
        if (o == null) return ""
        o.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return ""
        return buildString { for (i in 0 until runs.length()) append(runs.optJSONObject(i)?.optString("text").orEmpty()) }
    }
}
