package com.stemaker.nibsync.sync.webdav

import com.stemaker.nibsync.sync.RemoteEntry
import com.stemaker.nibsync.sync.RemoteListing
import com.stemaker.nibsync.sync.RemoteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import java.io.IOException
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory

/** The server answered with an unexpected HTTP status. */
open class RemoteException(val status: Int, message: String) : IOException(message)

/** Login is no longer valid (e.g. app password revoked). */
class AuthenticationException(status: Int) : RemoteException(status, "Not authorised ($status)")

/**
 * [RemoteStore] for a folder in a Nextcloud account, over WebDAV. Uses the
 * `remote.php/webdav` endpoint, which addresses the logged-in user's files regardless of
 * whether the login name equals the user id.
 *
 * @param serverUrl e.g. `https://cloud.example.org` (a sub path is fine).
 * @param folder sync folder inside the user's files, e.g. `NibSync` or `Shared/Notes`.
 */
class WebDavRemoteStore(
    serverUrl: String,
    loginName: String,
    appPassword: String,
    folder: String,
    private val client: OkHttpClient = OkHttpClient(),
) : RemoteStore {
    private val auth = Credentials.basic(loginName, appPassword)
    private val base: HttpUrl = serverUrl.trimEnd('/').toHttpUrl().newBuilder()
        .addPathSegments("remote.php/webdav")
        .apply { folder.split('/').filter { it.isNotBlank() }.forEach { addPathSegment(it) } }
        .build()

    private fun url(path: String): HttpUrl = base.newBuilder()
        .apply { path.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) } }
        .build()

    private suspend fun call(request: Request): Response = withContext(Dispatchers.IO) {
        client.newCall(request.newBuilder().header("Authorization", auth).build()).execute()
    }

    private fun fail(response: Response, what: String): Nothing {
        val code = response.code
        response.close()
        if (code == 401 || code == 403) throw AuthenticationException(code)
        throw RemoteException(code, "$what failed: HTTP $code")
    }

    override suspend fun list(path: String): RemoteListing? {
        val request = Request.Builder()
            .url(url(path))
            .header("Depth", "1")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
            .build()
        call(request).use { response ->
            if (response.code == 404) return null
            if (response.code != 207) fail(response, "List $path")
            return parseListing(response.body.string(), url(path).encodedPath)
        }
    }

    override suspend fun read(path: String, offset: Long): ByteArray {
        val builder = Request.Builder().url(url(path)).get()
        if (offset > 0) builder.header("Range", "bytes=$offset-")
        call(builder.build()).use { response ->
            return when (response.code) {
                206 -> response.body.bytes()
                200 -> response.body.bytes().let { if (offset > 0) it.copyOfRange(offset.toInt().coerceAtMost(it.size), it.size) else it }
                416 -> ByteArray(0) // nothing beyond offset
                else -> fail(response, "Read $path")
            }
        }
    }

    override suspend fun write(path: String, bytes: ByteArray) {
        val request = Request.Builder().url(url(path)).put(bytes.toRequestBody(OCTETS)).build()
        call(request).use { if (it.code !in 200..299) fail(it, "Write $path") }
    }

    override suspend fun makeDirectory(path: String) {
        val request = Request.Builder().url(url(path)).method("MKCOL", null).build()
        call(request).use { if (it.code != 201 && it.code != 405) fail(it, "Create folder $path") } // 405 = exists
    }

    override suspend fun delete(path: String) {
        val request = Request.Builder().url(url(path)).delete().build()
        call(request).use { if (it.code !in 200..299 && it.code != 404) fail(it, "Delete $path") }
    }

    companion object {
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val OCTETS = "application/octet-stream".toMediaType()

        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:getetag/><d:getcontentlength/><d:resourcetype/></d:prop></d:propfind>"""

        /** Parses a PROPFIND (depth 1) answer for the folder at [folderPath] (encoded URL path). */
        internal fun parseListing(xml: String, folderPath: String): RemoteListing {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            val self = folderPath.trimEnd('/')
            var folderEtag = ""
            val entries = ArrayList<RemoteEntry>()
            for (i in 0 until responses.length) {
                val r = responses.item(i) as Element
                val href = text(r, "href")?.let { hrefPath(it) }?.trimEnd('/') ?: continue
                val etag = text(r, "getetag")?.trim('"') ?: ""
                if (href == self) {
                    folderEtag = etag
                    continue
                }
                val isDir = r.getElementsByTagNameNS("DAV:", "collection").length > 0
                val size = text(r, "getcontentlength")?.toLongOrNull() ?: 0L
                val name = URLDecoder.decode(href.substringAfterLast('/'), "UTF-8")
                entries += RemoteEntry(name, isDir, etag, size)
            }
            return RemoteListing(folderEtag, entries)
        }

        private fun text(parent: Element, name: String): String? {
            val nodes = parent.getElementsByTagNameNS("DAV:", name)
            return if (nodes.length == 0) null else nodes.item(0).textContent?.takeIf { it.isNotEmpty() }
        }

        /** href may be absolute (`http://host/path`) or a path. */
        private fun hrefPath(href: String): String =
            if (href.startsWith("http://") || href.startsWith("https://")) href.toHttpUrl().encodedPath else href
    }
}
