package fr.viktor.ambiance

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Petit serveur HTTP sur la TV : il sert la page de télécommande et reçoit les commandes
 * du téléphone. Il s'annonce sur le réseau local (mDNS) pour être trouvé sans saisir d'adresse.
 *
 * Un seul serveur par processus, démarré une fois et jamais arrêté : rouvrir l'appli
 * ne doit pas se battre pour le port avec l'instance précédente.
 */
class RemoteServer private constructor(private val ctx: Context) {

    companion object {
        const val PORT = 8765
        const val SERVICE_TYPE = "_ambiance._tcp"
        val NAME: String get() = "Ambiance ${Build.MODEL}"

        @Volatile private var shared: RemoteServer? = null

        @Synchronized
        fun shared(ctx: Context): RemoteServer =
            shared ?: RemoteServer(ctx.applicationContext).also { it.start(); shared = it }
    }

    interface Listener {
        fun onCommand(json: String)
    }

    @Volatile var listener: Listener? = null
    @Volatile var state: String = "{}"
    @Volatile var running = false
        private set

    private val pool = Executors.newFixedThreadPool(4)

    private fun start() {
        Thread({
            // Le port peut rester occupé quelques secondes après un arrêt brutal : on réessaie
            repeat(30) {
                try {
                    val s = ServerSocket()
                    s.reuseAddress = true
                    s.bind(InetSocketAddress(PORT))
                    running = true
                    while (true) {
                        val client = s.accept()
                        pool.execute { handle(client) }
                    }
                } catch (_: Exception) {
                    running = false
                    Thread.sleep(2_000)
                }
            }
        }, "ambiance-http").apply { isDaemon = true }.start()
        announce()
    }

    private fun announce() {
        val manager = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val info = NsdServiceInfo().apply {
            serviceName = NAME
            serviceType = SERVICE_TYPE
            port = PORT
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    // ---------- HTTP minimal ----------

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                s.soTimeout = 8_000
                val input = s.getInputStream()
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')

                var length = 0
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0 && line.substring(0, idx).trim().equals("Content-Length", true)) {
                        length = line.substring(idx + 1).trim().toIntOrNull() ?: 0
                    }
                }
                val body = if (length in 1..65_536) readBytes(input, length).toString(Charsets.UTF_8) else ""
                val out = s.getOutputStream()

                when {
                    method == "GET" && (path == "/" || path == "/remote") -> asset(out, "web/remote.html")
                    method == "GET" && path == "/moods.js" -> asset(out, "web/moods.js")
                    method == "GET" && path == "/api/hello" -> send(out, 200, "application/json",
                        JSONObject().put("app", "ambiance").put("name", NAME).toString().toByteArray())
                    method == "GET" && path == "/api/state" -> send(out, 200, "application/json", state.toByteArray())
                    method == "POST" && path == "/api/cmd" -> {
                        val l = listener
                        if (l == null) {
                            send(out, 503, "application/json", """{"ok":false}""".toByteArray())
                        } else {
                            // Re-sérialisé pour ne transmettre à la page qu'un objet JSON valide
                            l.onCommand(JSONObject(body).toString())
                            send(out, 200, "application/json", """{"ok":true}""".toByteArray())
                        }
                    }
                    else -> send(out, 404, "text/plain", "Introuvable".toByteArray())
                }
            }
        } catch (_: Exception) {
            // Client déconnecté ou requête invalide : rien à faire
        }
    }

    private fun asset(out: OutputStream, path: String) {
        val bytes = ctx.assets.open(path).use { it.readBytes() }
        send(out, 200, Web.mime(path) + "; charset=utf-8", bytes)
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 404 -> "Not Found"; 503 -> "Unavailable"; else -> "Bad Request" }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 8_192) return null
        }
        return buf.toString("UTF-8")
    }

    private fun readBytes(input: InputStream, n: Int): ByteArray {
        val data = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(data, off, n - off)
            if (r == -1) break
            off += r
        }
        return data.copyOf(off)
    }
}
