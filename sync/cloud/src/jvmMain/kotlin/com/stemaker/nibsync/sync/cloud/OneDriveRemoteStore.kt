package com.stemaker.nibsync.sync.cloud

import com.stemaker.nibsync.sync.RemoteListing
import com.stemaker.nibsync.sync.RemoteStore
import com.stemaker.nibsync.sync.webdav.AuthenticationException
import com.stemaker.nibsync.sync.webdav.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URLEncoder

/**
 * [RemoteStore] for the app folder of a OneDrive ("Apps/NibSync", Microsoft Graph, permission
 * Files.ReadWrite.AppFolder: NibSync sees nothing else). Listings come from a [RemoteIndex]
 * that is brought up to date with Graph's delta query whenever the sync folder itself is
 * listed, i.e. once per sync run; where delta is not available the folder is read fully.
 */
class OneDriveRemoteStore(
    private val tokens: AccessTokens,
    private val http: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = GRAPH,
) : RemoteStore {
    private val lock = Mutex()
    private var index: RemoteIndex? = null
    private var deltaLink: String? = null
    private var deltaSupported = true

    override suspend fun list(path: String): RemoteListing? = lock.withLock {
        val i = if (path.isEmpty()) refresh() else index ?: refresh()
        i.listing(path)
    }

    override suspend fun read(path: String, offset: Long): ByteArray = lock.withLock {
        val item = (index ?: refresh()).file(path) ?: throw RemoteException(404, "Not found: $path")
        if (offset >= item.size && offset > 0) return ByteArray(0)
        val request = Request.Builder().url("$baseUrl/me/drive/items/${item.id}/content").apply {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.build()
        call(request) { response ->
            when (response.code) {
                206 -> response.body.bytes()
                200 -> response.body.bytes().let { if (offset > 0) it.copyOfRange(minOf(offset.toInt(), it.size), it.size) else it }
                416 -> ByteArray(0)
                else -> fail(response, path)
            }
        }
    }

    override suspend fun write(path: String, bytes: ByteArray) = lock.withLock {
        val i = index ?: refresh()
        val parent = i.folderIds(path.substringBeforeLast('/', "")).firstOrNull() ?: throw RemoteException(409, "Parent of $path missing")
        val name = encode(path.substringAfterLast('/'))
        val request = Request.Builder().url("$baseUrl/me/drive/items/$parent:/$name:/content").put(bytes.toRequestBody(OCTETS)).build()
        call(request) { response ->
            if (!response.isSuccessful) fail(response, path)
            i.put(item(json.parseToJsonElement(response.body.string()).jsonObject, fallbackParent = parent))
        }
    }

    override suspend fun makeDirectory(path: String) = lock.withLock {
        val i = index ?: refresh()
        if (path.isEmpty() || i.folderIds(path).isNotEmpty()) return
        val parent = i.folderIds(path.substringBeforeLast('/', "")).firstOrNull() ?: throw RemoteException(409, "Parent of $path missing")
        val name = path.substringAfterLast('/')
        val body = buildJsonObject {
            put("name", name)
            put("folder", JsonObject(emptyMap()))
            put("@microsoft.graph.conflictBehavior", "fail")
        }.toString().toRequestBody(JSON)
        call(Request.Builder().url("$baseUrl/me/drive/items/$parent/children").post(body).build()) { response ->
            when {
                response.isSuccessful -> i.put(item(json.parseToJsonElement(response.body.string()).jsonObject, fallbackParent = parent))
                // Made meanwhile by another device: fetch it.
                response.code == 409 -> call(Request.Builder().url("$baseUrl/me/drive/items/$parent:/${encode(name)}").build()) { existing ->
                    if (!existing.isSuccessful) fail(existing, path)
                    i.put(item(json.parseToJsonElement(existing.body.string()).jsonObject, fallbackParent = parent))
                }
                else -> fail(response, path)
            }
        }
    }

    override suspend fun delete(path: String) = lock.withLock {
        val i = index ?: refresh()
        for (item in i.itemsAt(path).filter { it.id != i.rootId }) {
            call(Request.Builder().url("$baseUrl/me/drive/items/${item.id}").delete().build()) { response ->
                if (!response.isSuccessful && response.code != 404) fail(response, path)
            }
            i.remove(item.id)
        }
    }

    /** Brings the index up to date (delta query, or a full scan). Caller holds the lock. */
    private suspend fun refresh(): RemoteIndex {
        val current = index ?: RemoteIndex(rootItem()).also { index = it }
        if (deltaSupported) {
            try {
                var url: String? = deltaLink ?: "$baseUrl/me/drive/special/approot/delta"
                while (url != null) {
                    val page = getJson(url)
                    for (value in page["value"]?.jsonArray.orEmpty()) {
                        val o = value.jsonObject
                        val id = o["id"]?.jsonPrimitive?.content ?: continue
                        if ("deleted" in o) current.remove(id) else current.put(item(o, fallbackParent = null))
                    }
                    url = page["@odata.nextLink"]?.jsonPrimitive?.contentOrNull
                    page["@odata.deltaLink"]?.jsonPrimitive?.contentOrNull?.let { deltaLink = it }
                }
                return current
            } catch (e: RemoteException) {
                if (e is AuthenticationException || e.status !in DELTA_UNSUPPORTED) throw e
                deltaSupported = false
            }
        }
        // Without delta: read the whole sync folder again.
        current.clear()
        current.put(RemoteIndex.Item(current.rootId, "", null, true, "", 0, ""))
        scan(current, current.rootId)
        return current
    }

    private suspend fun scan(i: RemoteIndex, folderId: String) {
        var url: String? = "$baseUrl/me/drive/items/$folderId/children"
        while (url != null) {
            val page = getJson(url)
            for (value in page["value"]?.jsonArray.orEmpty()) {
                val item = item(value.jsonObject, fallbackParent = folderId)
                i.put(item)
                if (item.isDirectory) scan(i, item.id)
            }
            url = page["@odata.nextLink"]?.jsonPrimitive?.contentOrNull
        }
    }

    private suspend fun rootItem(): String = getJson("$baseUrl/me/drive/special/approot")["id"]!!.jsonPrimitive.content

    private fun item(o: JsonObject, fallbackParent: String?): RemoteIndex.Item {
        val id = o["id"]!!.jsonPrimitive.content
        val parent = o["parentReference"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull ?: fallbackParent
        val isDir = "folder" in o || "root" in o
        val version = (o["cTag"] ?: o["eTag"])?.jsonPrimitive?.contentOrNull ?: ""
        return RemoteIndex.Item(
            id = id,
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
            parentId = if (id == index?.rootId) null else parent,
            isDirectory = isDir,
            version = version,
            size = o["size"]?.jsonPrimitive?.longOrNull ?: 0,
            modified = o["lastModifiedDateTime"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    }

    private suspend fun getJson(url: String): JsonObject = call(Request.Builder().url(url).build()) { response ->
        if (!response.isSuccessful) fail(response, url)
        json.parseToJsonElement(response.body.string()).jsonObject
    }

    /** Runs a request with the access token; after a 401 once more with a fresh token. */
    private suspend fun <T> call(request: Request, handle: suspend (Response) -> T): T = withContext(Dispatchers.IO) {
        var token = tokens.current()
        repeat(2) { attempt ->
            val response = http.newCall(request.newBuilder().header("Authorization", "Bearer $token").build()).execute()
            response.use {
                if (it.code == 401 && attempt == 0) {
                    token = tokens.refresh()
                } else {
                    // 403 on the app folder itself: the permission was taken back.
                    if (it.code == 401 || (it.code == 403 && request.url.encodedPath.endsWith("/approot"))) throw AuthenticationException(it.code)
                    return@withContext handle(it)
                }
            }
        }
        throw AuthenticationException(401)
    }

    private fun fail(response: Response, what: String): Nothing =
        throw RemoteException(response.code, "OneDrive ${response.code} for $what")

    private fun encode(name: String) = URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    companion object {
        const val GRAPH = "https://graph.microsoft.com/v1.0"
        val SCOPES = listOf("Files.ReadWrite.AppFolder", "offline_access", "openid", "email", "profile")
        private val OCTETS = "application/octet-stream".toMediaType()
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        /** Answers meaning "delta not available here" (e.g. app folders of work accounts). */
        private val DELTA_UNSUPPORTED = setOf(400, 403, 404, 405, 501)
    }
}
