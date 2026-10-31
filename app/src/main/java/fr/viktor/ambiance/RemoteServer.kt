package fr.viktor.ambiance

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Petit serveur HTTP sur la TV : il sert la page de télécommande et reçoit les commandes
 * du téléphone. Il s'annonce sur le réseau local (mDNS) pour être trouvé sans saisir d'adresse.
 */
class RemoteServer(private val ctx: Context, val port: Int = PORT) {

    companion object {
        const val PORT = 8765
        const val SERVICE_TYPE = "_ambiance._tcp"
    }

    interface Listener {
        fun onCommand(json: String)
    }

    @Volatile var listener: Listener? = null
    @Volatile var state: String = "{}"

    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    fun start() {
        Thread({
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(java.net.InetSocketAddress(port))
                server = s
                while (!s.isClosed) {
                    val client = s.accept()
                    pool.execute { handle(client) }
                }
            } catch (_: Exception) {
                // Socket fermé à l'arrêt, ou port déjà pris : la télécommande sera indisponible
            }
        }, "ambiance-http").start()
        announce()
    }

    fun stop() {
        runCatching { server?.close() }
        runCatching { nsdListener?.let { nsd?.unregisterService(it) } }
        nsdListener = null
        pool.shutdownNow()
    }

    private fun announce() {
        val manager = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "Ambiance ${Build.MODEL}"
            serviceType = SERVICE_TYPE
            port = this@RemoteServer.port
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
        nsd = manager
        nsdListener = l
    }

    // ---------- HTTP minimal ----------

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
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
                    method == "GET" && path == "/api/state" -> send(out, 200, "application/json", state.toByteArray())
                    method == "POST" && path == "/api/cmd" -> {
                        // Re-sérialisé pour ne transmettre à la page qu'un objet JSON valide
                        val json = JSONObject(body).toString()
                        listener?.onCommand(json)
                        send(out, 200, "application/json", """{"ok":true}""".toByteArray())
                    }
                    else -> send(out, 404, "text/plain", "Introuvable".toByteArray())
                }
            } catch (_: Exception) {
                runCatching { send(s.getOutputStream(), 400, "text/plain", "Requête invalide".toByteArray()) }
            }
        }
    }

    private fun asset(out: OutputStream, path: String) {
        val bytes = ctx.assets.open(path).use { it.readBytes() }
        send(out, 200, Web.mime(path) + "; charset=utf-8", bytes)
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 404 -> "Not Found"; else -> "Bad Request" }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
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
