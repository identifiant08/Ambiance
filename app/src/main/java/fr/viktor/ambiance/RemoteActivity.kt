package fr.viktor.ambiance

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Le téléphone en télécommande : trouve les TV qui font tourner Ambiance sur le Wi-Fi,
 * puis affiche la page de télécommande servie par la TV.
 *
 * Deux méthodes de recherche en parallèle : l'annonce mDNS de la TV, et un balayage
 * des adresses du réseau local (beaucoup de box bloquent le mDNS).
 */
class RemoteActivity : Activity() {

    private data class Tv(val name: String, val host: String, val port: Int)

    private lateinit var web: WebView
    private val found = ConcurrentHashMap<String, Tv>()        // clé : adresse IP
    private val toResolve = ConcurrentLinkedQueue<NsdServiceInfo>()
    @Volatile private var resolving = false
    @Volatile private var scanning = false
    @Volatile private var destroyed = false
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null
    private val main = Handler(Looper.getMainLooper())
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
                    // La TV a cessé de répondre (éteinte, autre réseau) : retour à la recherche
                    if (request.isForMainFrame && !onFinder && !destroyed) {
                        Toast.makeText(this@RemoteActivity, "La TV ne répond plus", Toast.LENGTH_SHORT).show()
                        showFinder()
                    }
                }
            }
            addJavascriptInterface(Finder(), "Finder")
        }
        setContentView(web)
        showFinder()
        startDiscovery()
        scan()

        // Reconnexion automatique à la dernière TV si elle répond
        val last = Prefs.lastTv
        if (last.isNotBlank()) io.launch {
            if (hello(last, 1_500) != null) main.post { if (!destroyed && onFinder) openRemote(last) }
        }
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onDestroy() {
        destroyed = true
        runCatching { discovery?.let { nsd?.stopServiceDiscovery(it) } }
        discovery = null
        runCatching { multicast?.release() }
        main.removeCallbacksAndMessages(null)
        io.cancel()
        web.removeJavascriptInterface("Finder")
        web.stopLoading()
        (web.parent as? android.view.ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!onFinder) showFinder() else super.onBackPressed()
    }

    private fun showFinder() {
        if (destroyed) return
        onFinder = true
        web.loadUrl(Web.FINDER_PAGE)
    }

    private fun openRemote(hostPort: String) {
        if (destroyed) return
        onFinder = false
        web.loadUrl("http://$hostPort/")
    }

    /** Vérifie qu'une adresse est bien une TV Ambiance. Renvoie son nom, ou null. */
    private fun hello(hostPort: String, timeout: Int): String? = runCatching {
        val c = URL("http://$hostPort/api/hello").openConnection() as HttpURLConnection
        c.connectTimeout = timeout
        c.readTimeout = timeout
        try {
            if (c.responseCode != 200) return null
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            if (o.optString("app") == "ambiance") o.optString("name") else null
        } finally {
            c.disconnect()
        }
    }.getOrNull()

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
                c.readTimeout = 4_000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(JSONObject().put("a", "url").put("url", link).toString().toByteArray()) }
                val code = c.responseCode
                c.disconnect()
                code == 200
            }.getOrDefault(false)
            main.post {
                if (!destroyed) Toast.makeText(this@RemoteActivity, if (ok) "Envoyé sur la TV" else "La TV ne répond pas", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- Balayage du réseau local ----------

    private fun scan() {
        if (scanning) return
        val ip = Web.localIp() ?: return
        val prefix = ip.substringBeforeLast('.')
        scanning = true
        scanJob = io.launch {
            try {
                val gate = Semaphore(48)
                (1..254).map { n ->
                    async {
                        val host = "$prefix.$n"
                        if (host == ip || !isActive) return@async
                        gate.withPermit {
                            hello("$host:${RemoteServer.PORT}", 700)?.let { name ->
                                found[host] = Tv(name, host, RemoteServer.PORT)
                            }
                        }
                    }
                }.awaitAll()
            } finally {
                scanning = false
            }
        }
    }

    // ---------- Découverte mDNS ----------

    private fun startDiscovery() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicast = runCatching {
            wifi?.createMulticastLock("ambiance")?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        val manager = getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = manager
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (destroyed) return
                toResolve.add(info)
                main.post { resolveNext() }
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        discovery = l
        runCatching { manager.discoverServices(RemoteServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    /** NsdManager ne résout qu'un service à la fois : on les traite en file, sur le fil principal. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || destroyed) return
        val info = toResolve.poll() ?: return
        resolving = true
        val ok = runCatching {
            nsd?.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        // Une autre résolution est en cours : on réessaie un peu plus tard, sans boucler
                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) main.postDelayed({ toResolve.add(serviceInfo); resolveNext() }, 800)
                        else resolveNext()
                    }
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress
                    if (host != null && !host.contains(':')) {
                        found[host] = Tv(serviceInfo.serviceName, host, serviceInfo.port)
                    }
                    main.post { resolving = false; resolveNext() }
                }
            })
        }.isSuccess
        if (!ok) resolving = false
    }

    // ---------- Pont avec les pages ----------

    inner class Finder {
        @JavascriptInterface
        fun list(): String = JSONArray(found.values.sortedBy { it.name }.map { tv ->
            JSONObject()
                .put("name", tv.name.removePrefix("Ambiance ").ifBlank { "TV" })
                .put("host", tv.host)
                .put("port", tv.port)
        }).toString()

        @JavascriptInterface
        fun isScanning(): Boolean = scanning || resolving

        @JavascriptInterface
        fun rescan() = main.post { if (!destroyed) scan() }

        @JavascriptInterface
        fun connect(host: String, port: Int) {
            val hp = "$host:${if (port > 0) port else RemoteServer.PORT}"
            io.launch {
                val name = hello(hp, 2_500)
                main.post {
                    if (destroyed) return@post
                    if (name != null) {
                        Prefs.lastTv = hp
                        openRemote(hp)
                    } else {
                        Toast.makeText(this@RemoteActivity,
                            "Rien à $hp. Vérifie qu'Ambiance est ouverte sur la TV.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        @JavascriptInterface
        fun openPlayer() = main.post {
            if (!destroyed) startActivity(Intent(this@RemoteActivity, PlayerActivity::class.java))
        }

        @JavascriptInterface
        fun showFinder() = main.post { this@RemoteActivity.showFinder() }
    }
}
