package fr.viktor.ambiance

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Lecteur d'ambiance : file d'attente de vidéos YouTube, résolues une à une par yt-dlp
 * puis lues par ExoPlayer (flux vidéo et audio séparés, fusionnés à la lecture).
 * La vidéo suivante est préparée pendant que la courante joue.
 */
@OptIn(UnstableApi::class)
class Engine(ctx: Context, private val onEvent: (JSONObject) -> Unit) {

    val player: ExoPlayer = ExoPlayer.Builder(ctx)
        .setLoadControl(
            DefaultLoadControl.Builder()
                // Tampon généreux : le Wi-Fi d'un salon fluctue, l'image ne doit pas figer
                .setBufferDurationsMs(20_000, 90_000, 2_500, 5_000)
                .build()
        )
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var prefetchJob: Job? = null

    private var entries: List<Entry> = emptyList()
    private var order: MutableList<Int> = mutableListOf()
    private var cursor = 0
    private var shuffle = false
    private var retries = 0
    private var skipsInARow = 0
    private var label = ""

    var maxHeight: Int = Prefs.quality

    private val current: Entry? get() = order.getOrNull(cursor)?.let { entries.getOrNull(it) }

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> emit("buffering")
                    Player.STATE_READY -> {
                        retries = 0
                        skipsInARow = 0
                        emit(if (player.playWhenReady) "playing" else "paused")
                        prefetchNext()
                    }
                    Player.STATE_ENDED -> advance(1)
                    else -> {}
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player.playbackState == Player.STATE_READY) emit(if (isPlaying) "playing" else "paused")
            }

            override fun onPlayerError(error: PlaybackException) = recover(error)
        })
    }

    // ---------- Commandes ----------

    fun play(type: String, id: String, title: String, shuffleOn: Boolean) {
        job?.cancel(); prefetchJob?.cancel()
        player.stop()
        label = title
        shuffle = shuffleOn
        job = scope.launch {
            emit("resolving", message = if (type == "playlist") "Je récupère la playlist" else "Je prépare la vidéo")
            val list = try {
                if (type == "playlist") withContext(Dispatchers.IO) { Resolver.playlist(id) }
                else listOf(Entry(id, title))
            } catch (e: Exception) {
                emit("error", message = "Playlist illisible : ${short(e)}")
                return@launch
            }
            if (list.isEmpty()) {
                emit("error", message = "Cette playlist est vide ou privée")
                return@launch
            }
            entries = list
            buildOrder(startAt = if (shuffleOn) (list.indices).random() else 0)
            startCurrent(0L)
        }
    }

    fun toggle() {
        if (player.playbackState == Player.STATE_IDLE && current != null) { startCurrent(0L); return }
        player.playWhenReady = !player.playWhenReady
        emit(if (player.playWhenReady) "playing" else "paused")
    }

    fun next() = advance(1)

    fun prev() {
        if (player.currentPosition > 5_000) { player.seekTo(0); return }
        advance(-1)
    }

    fun setShuffle(on: Boolean) {
        if (entries.isEmpty()) { shuffle = on; return }
        val now = order.getOrNull(cursor) ?: 0
        shuffle = on
        buildOrder(startAt = now)
        prefetchNext()
        emit(if (player.isPlaying) "playing" else "paused")
    }

    fun setVolume(percent: Int) {
        player.volume = (percent.coerceIn(0, 100)) / 100f
    }

    fun stop() {
        job?.cancel(); prefetchJob?.cancel()
        player.stop()
        player.clearMediaItems()
        entries = emptyList(); order.clear(); cursor = 0
    }

    fun release() {
        scope.cancel()
        player.release()
    }

    // ---------- Mécanique interne ----------

    private fun buildOrder(startAt: Int) {
        val all = entries.indices.toMutableList()
        order = if (shuffle) {
            all.remove(startAt)
            all.shuffle()
            (listOf(startAt) + all).toMutableList()
        } else all
        cursor = if (shuffle) 0 else startAt
    }

    private fun advance(step: Int) {
        if (order.isEmpty()) return
        // Une seule vidéo : on la relance en boucle
        if (order.size == 1) { player.seekTo(0); player.playWhenReady = true; return }
        cursor = (cursor + step + order.size) % order.size
        startCurrent(0L)
    }

    private fun startCurrent(positionMs: Long) {
        val e = current ?: return
        job?.cancel()
        job = scope.launch {
            emit("resolving", message = "Je prépare « ${e.title.take(60)} »")
            val s = try {
                withContext(Dispatchers.IO) { Resolver.streams(e.id, maxHeight) }
            } catch (ex: Exception) {
                skip("Vidéo indisponible, je passe à la suivante", ex)
                return@launch
            }
            player.setMediaSource(source(s, e), positionMs)
            player.prepare()
            player.playWhenReady = true
        }
    }

    private fun source(s: Streams, e: Entry): MediaSource {
        fun factory(headers: Map<String, String>): ProgressiveMediaSource.Factory {
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(headers["User-Agent"] ?: "Mozilla/5.0")
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
            return ProgressiveMediaSource.Factory(ChunkedDataSource.Factory(http))
        }
        val video = factory(s.videoHeaders).createMediaSource(
            MediaItem.Builder().setMediaId(e.id).setUri(s.video).build()
        )
        val audioUrl = s.audio ?: return video
        val audio = factory(s.audioHeaders.ifEmpty { s.videoHeaders }).createMediaSource(MediaItem.fromUri(audioUrl))
        return MergingMediaSource(true, true, video, audio)
    }

    private fun prefetchNext() {
        if (order.size < 2) return
        val nextEntry = entries.getOrNull(order[(cursor + 1) % order.size]) ?: return
        val h = maxHeight
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            runCatching { Resolver.streams(nextEntry.id, h) }
        }
    }

    /** Les adresses YouTube expirent : on les régénère et on reprend au même endroit. */
    private fun recover(error: PlaybackException) {
        val e = current ?: return
        val pos = player.currentPosition
        if (retries < 2) {
            retries++
            Resolver.invalidate(e.id)
            emit("buffering", message = "Reconnexion au flux")
            startCurrent(pos)
        } else {
            skip("Lecture impossible, je passe à la suivante", error)
        }
    }

    private fun skip(msg: String, cause: Exception) {
        retries = 0
        skipsInARow++
        // Si tout échoue (yt-dlp obsolète, réseau coupé), on s'arrête au lieu de boucler
        if (order.size <= 1 || skipsInARow >= minOf(order.size, 5)) {
            emit("error", message = "Lecture impossible : ${short(cause)}. Essaie de mettre à jour yt-dlp dans Réglages.")
            return
        }
        emit("resolving", message = msg)
        cursor = (cursor + 1) % order.size
        startCurrent(0L)
    }

    private fun short(e: Exception): String =
        (e.message ?: e.javaClass.simpleName).lines().lastOrNull { it.isNotBlank() }?.take(140) ?: "erreur inconnue"

    private fun emit(status: String, message: String? = null) {
        val o = JSONObject()
            .put("status", status)
            .put("playing", player.playWhenReady && player.playbackState == Player.STATE_READY)
            .put("shuffle", shuffle)
            .put("index", cursor)
            .put("count", order.size)
        current?.let { o.put("title", it.title) }
        if (message != null) o.put("message", message)
        onEvent(o)
    }
}
