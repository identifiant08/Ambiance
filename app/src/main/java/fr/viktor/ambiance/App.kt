package fr.viktor.ambiance

import android.app.Application
import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class App : Application() {

    companion object {
        /** Terminé quand Python et yt-dlp sont prêts (1er lancement : quelques secondes). */
        val ready = CompletableDeferred<Result<Unit>>()

        @Volatile
        var ytdlpVersion: String = ""

        lateinit var instance: App
            private set

        /** Une mise à jour de yt-dlp ne doit jamais remplacer les fichiers pendant qu'il tourne. */
        private val ytLock = ReentrantReadWriteLock()

        fun ytdlp(req: YoutubeDLRequest): YoutubeDLResponse =
            ytLock.read { YoutubeDL.getInstance().execute(req) }

        /** Met yt-dlp à jour (canal nightly, le plus réactif aux changements de YouTube). */
        fun updateYtDlp(ctx: Context): String = ytLock.write {
            val status = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.NIGHTLY)
            Prefs.lastUpdate = System.currentTimeMillis()
            ytdlpVersion = YoutubeDL.getInstance().version(ctx).orEmpty()
            when (status) {
                YoutubeDL.UpdateStatus.DONE -> "yt-dlp mis à jour ($ytdlpVersion)"
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp est déjà à jour ($ytdlpVersion)"
                else -> "Mise à jour terminée"
            }
        }

        private fun crashFile(ctx: Context) = File(ctx.filesDir, "dernier-plantage.txt")

        /** Rapport du dernier plantage (puis effacé), ou null s'il n'y en a pas. */
        fun takeLastCrash(ctx: Context): String? {
            val f = crashFile(ctx)
            if (!f.exists()) return null
            val text = runCatching { f.readText() }.getOrNull()
            f.delete()
            return text?.take(1800)
        }
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)

        // Journal des plantages, affiché au lancement suivant
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                crashFile(this).writeText(
                    "Version ${BuildConfig.VERSION_NAME}, fil ${thread.name}\n${Log.getStackTraceString(e)}"
                )
            }
            previous?.uncaughtException(thread, e)
        }

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
