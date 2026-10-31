package fr.viktor.ambiance

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Le téléphone en télécommande : trouve les TV qui font tourner Ambiance sur le Wi-Fi,
 * puis affiche la page de télécommande servie par la TV.
 */
class RemoteActivity : Activity() {

    private lateinit var web: WebView
    private val found = ConcurrentHashMap<String, Pair<String, Int>>()
    private val toResolve = ConcurrentLinkedQueue<NsdServiceInfo>()
    @Volatile private var resolving = false
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var onFinder = true

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0D1524.toInt()
        window.navigationBarColor = 0xFF0D1524.toInt()

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0D1524"))
            Web.setup(this)
            webViewClient = object : AssetClient(this@RemoteActivity) {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    // La TV ne répond pas (éteinte, autre réseau) : retour à la recherche
                    if (request.isForMainFrame && !onFinder) {
                        Toast.makeText(this@RemoteActivity, "La TV ne répond pas", Toast.LENGTH_SHORT).show()
                        showFinder()
                    }
                }
            }
            addJavascriptInterface(Finder(), "Finder")
        }
        setContentView(web)
        startDiscovery()

        val last = Prefs.lastTv
        if (last.isNotBlank()) openRemote(last) else showFinder()
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onDestroy() {
        runCatching { discovery?.let { nsd?.stopServiceDiscovery(it) } }
        runCatching { multicast?.release() }
        io.cancel()
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!onFinder) showFinder() else super.onBackPressed()
    }

    private fun showFinder() {
        onFinder = true
        web.loadUrl(Web.FINDER_PAGE)
    }

    private fun openRemote(hostPort: String) {
        onFinder = false
        web.loadUrl("http://$hostPort/")
    }

    // ---------- Partage d'un lien depuis l'appli YouTube : il part direct sur la TV ----------

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val link = Regex("""https?://\S+""").find(text)?.value ?: return
        val tv = Prefs.lastTv
        if (tv.isBlank()) {
            Toast.makeText(this, "Connecte d'abord une TV", Toast.LENGTH_LONG).show()
            return
        }
        io.launch {
            val ok = runCatching {
                val c = URL("http://$tv/api/cmd").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 4_000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(JSONObject().put("a", "url").put("url", link).toString().toByteArray()) }
                val code = c.responseCode
                c.disconnect()
                code == 200
            }.getOrDefault(false)
            runOnUiThread {
                Toast.makeText(this@RemoteActivity, if (ok) "Envoyé sur la TV" else "La TV ne répond pas", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- Découverte des TV (mDNS) ----------

    private fun startDiscovery() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicast = wifi?.createMulticastLock("ambiance")?.apply { setReferenceCounted(false); acquire() }
        val manager = getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = manager
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                toResolve.add(info)
                resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName)
            }
        }
        discovery = l
        runCatching { manager.discoverServices(RemoteServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    /** NsdManager ne résout qu'un service à la fois : on les traite en file. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val info = toResolve.poll() ?: return
        resolving = true
        nsd?.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolving = false
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) toResolve.add(serviceInfo)
                resolveNext()
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress
                if (host != null && !host.contains(':')) found[serviceInfo.serviceName] = host to serviceInfo.port
                resolving = false
                resolveNext()
            }
        })
    }

    // ---------- Pont avec les pages ----------

    inner class Finder {
        @JavascriptInterface
        fun list(): String = JSONArray(found.map { (name, hp) ->
            JSONObject().put("name", name.removePrefix("Ambiance ").ifBlank { "TV" }).put("host", hp.first).put("port", hp.second)
        }).toString()

        @JavascriptInterface
        fun connect(host: String, port: Int) {
            val hp = "$host:${if (port > 0) port else RemoteServer.PORT}"
            Prefs.lastTv = hp
            runOnUiThread { openRemote(hp) }
        }

        @JavascriptInterface
        fun openPlayer() = runOnUiThread {
            startActivity(Intent(this@RemoteActivity, PlayerActivity::class.java))
        }

        @JavascriptInterface
        fun showFinder() = runOnUiThread { this@RemoteActivity.showFinder() }
    }
}
