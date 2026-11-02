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
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
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

        /**
         * Lance yt-dlp avec un délai maximum : sur le Mi Box, un yt-dlp bloqué
         * ne doit jamais laisser l'écran figé sur « Je prépare… ».
         */
        fun ytdlp(req: YoutubeDLRequest, timeoutSec: Long = 90): YoutubeDLResponse {
            // Cache des fichiers du lecteur YouTube : évite de tout recalculer à chaque vidéo
            req.addOption("--cache-dir", File(instance.cacheDir, "yt-dlp").absolutePath)
            req.addOption("--no-warnings")
            // Si une mise à jour est en cours, on ne l'attend pas indéfiniment
            val locked = ytLock.readLock().tryLock(20, TimeUnit.SECONDS)
            val id = "ytdlp-" + System.nanoTime()
            val watchdog = Thread {
                try {
                    Thread.sleep(timeoutSec * 1000)
                    runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
                } catch (_: InterruptedException) {
                }
            }.apply { isDaemon = true; start() }
            try {
                lastUse = System.currentTimeMillis()
                return YoutubeDL.getInstance().execute(req, id).also { lastUse = System.currentTimeMillis() }
            } catch (e: Exception) {
                if (!watchdog.isAlive) throw IllegalStateException("yt-dlp n'a pas répondu en $timeoutSec s")
                throw e
            } finally {
                watchdog.interrupt()
                if (locked) ytLock.readLock().unlock()
            }
        }

        /** Aucun appel yt-dlp en cours ou récent : on peut le mettre à jour sans gêner. */
        @Volatile var lastUse = 0L

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
            // Mise à jour automatique au plus une fois par jour, seulement quand
            // personne n'utilise yt-dlp depuis 2 minutes (jamais pendant un chargement)
            if (result.isSuccess && System.currentTimeMillis() - Prefs.lastUpdate > 24 * 3600_000L) {
                kotlinx.coroutines.delay(180_000L)
                while (System.currentTimeMillis() - lastUse < 120_000L) kotlinx.coroutines.delay(15_000L)
                runCatching { updateYtDlp(this@App) }
            }
        }
    }
}
