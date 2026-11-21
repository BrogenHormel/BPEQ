package app.bpeq.core.io

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * The HTTPS fetcher for [LinkImport]: GET only, no cookies or identifiers, a generic User-Agent, 15 s timeouts and
 * a 4 MB cap (EQ and measurement files are a few kB). 404/410 = missing file (null); other failures throw with a
 * message meant for the user.
 */
class HttpFetch(private val onRequest: (String) -> Unit = {}) : LinkImport.Fetch {
    override fun text(url: String): String? {
        require(url.startsWith("https://")) { "https only" }
        onRequest(url)
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 15_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "BPEQ")
            val code = c.responseCode
            val host = c.url.host
            when {
                code == 404 || code == 410 -> return null
                code == 401 || code == 403 -> throw ImportException(
                    "$host doesn't allow its files to be downloaded by other apps (HTTP $code). " +
                        "Download the file in your browser and load it in BPEQ instead.")
                code !in 200..299 -> throw ImportException("$host answered HTTP $code")
            }
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) throw ImportException("The file at that link is too large")
                }
            }
            return out.toString(Charsets.UTF_8.name())
        } catch (e: UnknownHostException) {
            throw ImportException("No internet connection (couldn't reach ${e.message})")
        } catch (e: SocketTimeoutException) {
            throw ImportException("The site didn't answer in time")
        } catch (e: IOException) {
            throw ImportException("Download failed: ${e.message}")
        } finally {
            c.disconnect()
        }
    }

    companion object { const val MAX_BYTES = 4 * 1024 * 1024 }
}
