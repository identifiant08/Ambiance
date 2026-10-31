package fr.viktor.ambiance

import android.app.Application
import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    companion object {
        /** Terminé quand Python et yt-dlp sont prêts (1er lancement : quelques secondes). */
        val ready = CompletableDeferred<Result<Unit>>()

        @Volatile
        var ytdlpVersion: String = ""

        lateinit var instance: App
            private set

        /** Met yt-dlp à jour (canal nightly, le plus réactif aux changements de YouTube). */
        fun updateYtDlp(ctx: Context): String {
            val status = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.NIGHTLY)
            Prefs.lastUpdate = System.currentTimeMillis()
            ytdlpVersion = YoutubeDL.getInstance().version(ctx).orEmpty()
            return when (status) {
                YoutubeDL.UpdateStatus.DONE -> "yt-dlp mis à jour ($ytdlpVersion)"
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp est déjà à jour ($ytdlpVersion)"
                else -> "Mise à jour terminée"
            }
        }
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        scope.launch {
            val result = runCatching {
                YoutubeDL.getInstance().init(this@App)
                ytdlpVersion = YoutubeDL.getInstance().version(this@App).orEmpty()
            }
            ready.complete(result)
            // Mise à jour automatique au plus une fois par jour
            if (result.isSuccess && System.currentTimeMillis() - Prefs.lastUpdate > 24 * 3600_000L) {
                runCatching { updateYtDlp(this@App) }
            }
        }
    }
}
