package fr.viktor.ambiance

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Écran principal sur la TV (et lecture locale sur le téléphone) :
 * la vidéo native en dessous, l'interface web transparente par-dessus.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity(), RemoteServer.Listener {

    private lateinit var web: WebView
    private lateinit var playerView: PlayerView
    private lateinit var engine: Engine
    private var server: RemoteServer? = null
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        engine = Engine(this) { event -> js("app.onPlayer(${event})") }

        playerView = PlayerView(this).apply {
            useController = false
            setShutterBackgroundColor(Color.BLACK)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            player = engine.player
            isFocusable = false
        }
        web = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            Web.setup(this)
            webViewClient = AssetClient(this@PlayerActivity)
            webChromeClient = WebChromeClient()
            addJavascriptInterface(Bridge(), "Android")
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(playerView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        // Après setContentView : avant, la fenêtre n'a pas encore de vue racine (plantage sur Android 11)
        hideSystemBars()
        web.loadUrl(Web.TV_PAGE)
        web.requestFocus()

        server = RemoteServer.shared(this).also { it.listener = this }
        // Au premier lancement, yt-dlp met quelques secondes à s'installer
        io.launch {
            App.ready.await()
            js("app.reloadConfig()")
        }
    }

    override fun onDestroy() {
        destroyed = true
        server?.let {
            if (it.listener === this) it.listener = null
            it.state = "{}"
        }
        io.cancel()
        engine.release()
        web.removeJavascriptInterface("Android")
        web.stopLoading()
        (web.parent as? android.view.ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.decorView.windowInsetsController?.let {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }
        }
    }

    /** Exécute du JS dans la page (toujours sur le fil principal). */
    private fun js(code: String) {
        if (destroyed) return
        runOnUiThread { if (!destroyed) web.evaluateJavascript("window.app && $code", null) }
    }

    // ---------- Télécommande physique ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val name = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "UP"
            KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
            KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "OK"
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> "BACK"
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> "PLAYPAUSE"
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "NEXT"
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> "PREV"
            else -> null
        } ?: return super.dispatchKeyEvent(event)

        if (destroyed || isFinishing) return true
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (name == "BACK") {
                // La page dit si elle a géré le retour ; sinon on quitte l'appli
                web.evaluateJavascript("window.app ? app.onKey('BACK') : false") { r ->
                    if (r != "true" && !isFinishing) finish()
                }
            } else {
                web.evaluateJavascript("window.app && app.onKey('$name')", null)
            }
        }
        return true
    }

    // ---------- Commandes du téléphone ----------

    override fun onCommand(json: String) = js("app.onRemote($json)")

    // ---------- Pont page <-> natif ----------

    inner class Bridge {
        private fun reply(id: String, payload: JSONObject) = js("app.onNative(${JSONObject.quote(id)}, $payload)")

        @JavascriptInterface
        fun getConfig(): String {
            val ip = Web.localIp()
            return JSONObject()
                .put("address", if (ip != null) "$ip:${RemoteServer.PORT}" else "")
                .put("quality", Prefs.quality)
                .put("ytdlp", App.ytdlpVersion)
                .put("ready", App.ready.isCompleted)
                .toString()
        }

        @JavascriptInterface
        fun search(id: String, query: String, kind: String) {
            io.launch {
                val payload = try {
                    val items = YtSearch.search(query, kind)
                    JSONObject().put("ok", true).put("items", JSONArray(items.map { it.toJson() }))
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message ?: "erreur réseau")
                }
                reply(id, payload)
            }
        }

        @JavascriptInterface
        fun play(json: String) {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return
            runOnUiThread {
                engine.maxHeight = Prefs.quality
                engine.play(o.optString("type"), o.optString("id"), o.optString("title"), o.optBoolean("shuffle"))
            }
        }

        @JavascriptInterface
        fun control(action: String, value: Int) {
            runOnUiThread {
                when (action) {
                    "toggle" -> engine.toggle()
                    "next" -> engine.next()
                    "prev" -> engine.prev()
                    "shuffle" -> engine.setShuffle(value == 1)
                    "volume" -> engine.setVolume(value)
                    "stop" -> engine.stop()
                }
            }
        }

        @JavascriptInterface
        fun setQuality(height: Int) {
            Prefs.quality = height
            runOnUiThread { engine.maxHeight = height }
        }

        @JavascriptInterface
        fun updateYtDlp(id: String) {
            io.launch {
                val payload = try {
                    App.ready.await().getOrThrow()
                    JSONObject().put("ok", true).put("message", App.updateYtDlp(applicationContext))
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message ?: "réseau indisponible")
                }
                reply(id, payload)
            }
        }

        @JavascriptInterface
        fun getLog(): String = Journal.text()

        @JavascriptInterface
        fun reportState(json: String) {
            server?.state = json
        }

        @JavascriptInterface
        fun showKeyboard() = runOnUiThread {
            web.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(web, InputMethodManager.SHOW_IMPLICIT)
        }

        @JavascriptInterface
        fun hideKeyboard() = runOnUiThread {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(web.windowToken, 0)
        }
    }
}
