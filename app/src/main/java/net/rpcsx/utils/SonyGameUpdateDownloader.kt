package net.rpcsx.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory

data class SonyGameUpdatePkg(
    val version: String,
    val sizeBytes: Long,
    val url: String,
    val sha1: String
) {
    val sizeFormatted: String
        get() {
            val mb = sizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1024) {
                String.format("%.2f GB", mb / 1024.0)
            } else {
                String.format("%.1f MB", mb)
            }
        }
}

object SonyGameUpdateDownloader {
    private const val TAG = "SonyUpdateDownloader"

    /**
     * TrustManager que bypasea validación SSL.
     * Necesario porque el servidor XML de Sony (a0.ww.np.dl.playstation.net)
     * usa un certificado firmado con SHA-1 (SCEI DNAS Root 05), que Android/
     * Conscrypt rechaza desde API 28 como "insecure hash function".
     * Mismo approach que PS3GameUpdateDownloader (bypass_ssl=True).
     */
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sslContext = SSLContext.getInstance("TLS").also {
        it.init(null, arrayOf(trustAllManager), SecureRandom())
    }

    /**
     * Cliente para el servidor XML de Sony — bypass SSL por cert SHA-1 legacy.
     */
    private val xmlClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /**
     * Cliente para descargar PKGs — HTTP plano, sin SSL.
     */
    private val pkgClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Cleans Title ID (e.g. "BLUS-31156" -> "BLUS31156")
     */
    fun cleanTitleId(titleId: String): String {
        return titleId.replace("-", "").trim().uppercase()
    }

    /**
     * Fetches official PS3 game updates from Sony's PSN server.
     */
    suspend fun fetchGameUpdates(rawTitleId: String): List<SonyGameUpdatePkg> = withContext(Dispatchers.IO) {
        val titleId = cleanTitleId(rawTitleId)
        if (titleId.length < 8) return@withContext emptyList()

        val xmlUrl = "https://a0.ww.np.dl.playstation.net/tpl/np/$titleId/$titleId-ver.xml"
        Log.i(TAG, "Fetching updates from Sony server: $xmlUrl")

        val request = Request.Builder()
            .url(xmlUrl)
            .header("User-Agent", "PS3Update/1.0")
            .build()

        try {
            val response = xmlClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Sony update server returned ${response.code} for $titleId")
                return@withContext emptyList()
            }

            val bodyBytes = response.body.bytes()
            if (bodyBytes.isEmpty()) {
                Log.i(TAG, "Sony server returned empty body for $titleId (no updates)")
                return@withContext emptyList()
            }

            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(bodyBytes.inputStream())
            doc.documentElement.normalize()

            val updateList = mutableListOf<SonyGameUpdatePkg>()
            val pkgNodes = doc.getElementsByTagName("package")

            for (i in 0 until pkgNodes.length) {
                val node = pkgNodes.item(i)
                if (node is Element) {
                    val version = node.getAttribute("version")
                    val sizeStr = node.getAttribute("size")
                    val url = node.getAttribute("url")
                    val sha1 = node.getAttribute("sha1sum")
                    val sizeBytes = sizeStr.toLongOrNull() ?: 0L
                    if (url.isNotEmpty()) {
                        updateList.add(SonyGameUpdatePkg(version, sizeBytes, url, sha1))
                    }
                }
            }

            Log.i(TAG, "Found ${updateList.size} updates for $titleId")
            return@withContext updateList.sortedBy { it.version }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Sony updates for $titleId", e)
            return@withContext emptyList()
        }
    }

    /**
     * Downloads the specified update PKG to external files dir.
     */
    suspend fun downloadUpdatePkg(
        context: Context,
        pkg: SonyGameUpdatePkg,
        onProgress: (Long, Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val fileName = pkg.url.substringAfterLast("/").ifEmpty { "update_${pkg.version}.pkg" }
        // getExternalFilesDir no requiere permisos y persiste entre sesiones
        val updatesDir = File(context.getExternalFilesDir(null), "game_updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()

        val destFile = File(updatesDir, fileName)
        Log.i(TAG, "Downloading PKG ${pkg.version} from ${pkg.url} to ${destFile.absolutePath}")

        val request = Request.Builder()
            .url(pkg.url)
            .header("User-Agent", "PS3Update/1.0")
            .build()

        try {
            val response = pkgClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to download PKG: ${response.code}")
                return@withContext null
            }

            val body = response.body
            val totalSize = body.contentLength().let { if (it > 0) it else pkg.sizeBytes }

            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    var downloaded = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        onProgress(downloaded, totalSize)
                    }
                    output.flush()
                }
            }

            Log.i(TAG, "Finished downloading PKG: ${destFile.absolutePath}")
            return@withContext destFile
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading PKG ${pkg.url}", e)
            if (destFile.exists()) destFile.delete()
            return@withContext null
        }
    }
}
