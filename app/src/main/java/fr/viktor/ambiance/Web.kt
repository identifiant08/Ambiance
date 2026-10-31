package fr.viktor.ambiance

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.NetworkInterface

object Web {
    /** Domaine réservé par Android pour servir les fichiers de l'appli comme un vrai site https. */
    const val HOST = "appassets.androidplatform.net"
    const val TV_PAGE = "https://$HOST/web/tv.html"
    const val FINDER_PAGE = "https://$HOST/web/finder.html"

    fun mime(path: String) = when (path.substringAfterLast('.')) {
        "html" -> "text/html"
        "js" -> "application/javascript"
        "css" -> "text/css"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun setup(web: WebView) {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
    }

    /** Adresse IPv4 locale de l'appareil (Wi-Fi ou Ethernet), pour la télécommande. */
    fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}

/** Sert les fichiers du dossier assets/web sous https://appassets.androidplatform.net/web/… */
open class AssetClient(private val ctx: Context) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.host != Web.HOST) return null
        val path = url.path?.removePrefix("/") ?: return null
        if (!path.startsWith("web/") || path.contains("..")) return null
        return try {
            WebResourceResponse(Web.mime(path), "utf-8", ctx.assets.open(path))
        } catch (e: Exception) {
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
}
