package fr.viktor.ambiance

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlin.math.min

/**
 * YouTube bride ou coupe les longues requêtes sans plage d'octets.
 * Comme yt-dlp, on lit le flux par morceaux de 10 Mo (requêtes Range successives),
 * ce qui permet de tenir des vidéos d'ambiance de 10 h.
 */
@OptIn(UnstableApi::class)
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkSize: Long = 10L * 1024 * 1024,
) : DataSource {

    private var spec: DataSpec? = null
    private var position = 0L
    private var end = -1L
    private var chunkLeft = 0L
    private var chunked = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        position = dataSpec.position
        // Les adresses googlevideo indiquent la taille totale dans le paramètre clen
        val clen = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        end = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.position + dataSpec.length
            clen != null -> clen
            else -> -1L
        }
        if (end < 0) {
            chunked = false
            return upstream.open(dataSpec)
        }
        chunked = true
        openChunk()
        return end - dataSpec.position
    }

    private fun openChunk() {
        val s = spec ?: return
        val len = min(chunkSize, end - position)
        chunkLeft = len
        upstream.open(s.subrange(position - s.position, len))
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!chunked) return upstream.read(buffer, offset, length)
        if (position >= end) return C.RESULT_END_OF_INPUT
        if (chunkLeft <= 0L) {
            upstream.close()
            openChunk()
        }
        var n = upstream.read(buffer, offset, min(length.toLong(), chunkLeft).toInt())
        if (n == C.RESULT_END_OF_INPUT) {
            // Morceau plus court que prévu : on enchaîne sur le suivant
            upstream.close()
            if (position >= end) return C.RESULT_END_OF_INPUT
            openChunk()
            n = upstream.read(buffer, offset, min(length.toLong(), chunkLeft).toInt())
            if (n == C.RESULT_END_OF_INPUT) return n
        }
        position += n
        chunkLeft -= n
        return n
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        upstream.close()
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedDataSource(upstream.createDataSource())
    }
}
