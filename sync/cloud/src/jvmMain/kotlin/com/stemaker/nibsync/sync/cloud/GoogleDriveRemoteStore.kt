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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * [RemoteStore] for a folder "NibSync" in "My Drive" of a Google Drive (API v3, scope
 * drive.file: NibSync only sees files it created itself). Listings come from a [RemoteIndex]
 * that is filled once and then kept up to date with the changes feed whenever the sync folder
 * itself is listed, i.e. once per sync run.
 */
class GoogleDriveRemoteStore(
    private val tokens: AccessTokens,
    private val http: OkHttpClient = OkHttpClient(),
    private val apiUrl: String = API,
    private val uploadUrl: String = UPLOAD,
    private val folderName: String = "NibSync",
) : RemoteStore {
    private val lock = Mutex()
    private var index: RemoteIndex? = null
    private var pageToken: String? = null

    override suspend fun list(path: String): RemoteListing? = lock.withLock {
        val i = if (path.isEmpty()) refresh() else index ?: refresh()
        i.listing(path)
    }

    override suspend fun read(path: String, offset: Long): ByteArray = lock.withLock {
        val item = (index ?: refresh()).file(path) ?: throw RemoteException(404, "Not found: $path")
        if (offset >= item.size && offset > 0) return ByteArray(0)
        val request = Request.Builder().url("$apiUrl/files/${item.id}?alt=media").apply {
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
        val existing = i.file(path)
        val response = if (existing != null) {
            call(Request.Builder().url("$uploadUrl/files/${existing.id}?uploadType=media&fields=$FIELDS").patch(bytes.toRequestBody(OCTETS)).build()) { readItem(it, path) }
        } else {
            val parent = i.folderIds(path.substringBeforeLast('/', "")).firstOrNull() ?: throw RemoteException(409, "Parent of $path missing")
            val metadata = buildJsonObject {
                put("name", path.substringAfterLast('/'))
                put("parents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(parent)) })
            }
            val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                .addPart(metadata.toString().toRequestBody(JSON))
                .addPart(bytes.toRequestBody(OCTETS))
                .build()
            call(Request.Builder().url("$uploadUrl/files?uploadType=multipart&fields=$FIELDS").post(body).build()) { readItem(it, path) }
        }
        i.put(item(response))
    }

    override suspend fun makeDirectory(path: String) = lock.withLock {
        val i = index ?: refresh()
        if (path.isEmpty() || i.folderIds(path).isNotEmpty()) return
        val parent = i.folderIds(path.substringBeforeLast('/', "")).firstOrNull() ?: throw RemoteException(409, "Parent of $path missing")
        i.put(item(createFolder(path.substringAfterLast('/'), parent)))
    }

    override suspend fun delete(path: String) = lock.withLock {
        val i = index ?: refresh()
        for (item in i.itemsAt(path).filter { it.id != i.rootId }) {
            call(Request.Builder().url("$apiUrl/files/${item.id}").delete().build()) { response ->
                if (!response.isSuccessful && response.code != 404) fail(response, path)
            }
            i.remove(item.id)
        }
    }

    /** Brings the index up to date. Caller holds the lock. */
    private suspend fun refresh(): RemoteIndex {
        val current = index
        if (current == null) {
            // First time: find (or make) the sync folder, read it fully, then follow changes.
            val token = getJson("$apiUrl/changes/startPageToken")["startPageToken"]!!.jsonPrimitive.content
            val root = findOrCreateRoot()
            val i = RemoteIndex(root["id"]!!.jsonPrimitive.content)
            i.put(item(root).copy(parentId = null))
            scan(i, i.rootId)
            index = i
            pageToken = token
            return i
        }
        var token = pageToken
        val pending = ArrayList<RemoteIndex.Item>()
        while (token != null) {
            val url = "$apiUrl/changes".toHttpUrl().newBuilder()
                .addQueryParameter("pageToken", token)
                .addQueryParameter("includeRemoved", "true")
                .addQueryParameter("spaces", "drive")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("fields", "nextPageToken,newStartPageToken,changes(fileId,removed,file($FIELDS,trashed))")
                .build().toString()
            val page = getJson(url)
            for (change in page["changes"]?.jsonArray.orEmpty()) {
                val c = change.jsonObject
                val id = c["fileId"]?.jsonPrimitive?.contentOrNull ?: continue
                val file = c["file"]?.jsonObject
                if (c["removed"]?.jsonPrimitive?.booleanOrNull == true || file == null || file["trashed"]?.jsonPrimitive?.booleanOrNull == true) {
                    current.remove(id)
                } else if (id != current.rootId) {
                    pending += item(file)
                }
            }
            page["newStartPageToken"]?.jsonPrimitive?.contentOrNull?.let { pageToken = it }
            token = page["nextPageToken"]?.jsonPrimitive?.contentOrNull
        }
        // Items whose parent is in the sync folder; parents may come after their children.
        var progress = true
        while (pending.isNotEmpty() && progress) {
            progress = false
            val it = pending.iterator()
            while (it.hasNext()) {
                val item = it.next()
                if (item.parentId == current.rootId || (item.parentId != null && current[item.parentId] != null)) {
                    current.put(item)
                    it.remove()
                    progress = true
                }
            }
        }
        // Moved out of the sync folder (or never in it): not ours.
        pending.forEach { current.remove(it.id) }
        return current
    }

    private suspend fun findOrCreateRoot(): JsonObject {
        val q = "name='$folderName' and mimeType='$FOLDER' and 'root' in parents and trashed=false"
        val url = "$apiUrl/files".toHttpUrl().newBuilder().addQueryParameter("q", q).addQueryParameter("fields", "files($FIELDS,createdTime)").build().toString()
        val found = getJson(url)["files"]?.jsonArray.orEmpty().map { it.jsonObject }
        // Several (made at the same time by two devices): the oldest one counts.
        return found.minByOrNull { it["createdTime"]?.jsonPrimitive?.contentOrNull ?: "" } ?: createFolder(folderName, "root")
    }

    private suspend fun createFolder(name: String, parent: String): JsonObject {
        val body = buildJsonObject {
            put("name", name)
            put("mimeType", FOLDER)
            put("parents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(parent)) })
        }.toString().toRequestBody(JSON)
        return call(Request.Builder().url("$apiUrl/files?fields=$FIELDS").post(body).build()) { readItem(it, name) }
    }

    private suspend fun scan(i: RemoteIndex, folderId: String) {
        var pageTokenForList: String? = null
        do {
            val url = "$apiUrl/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", "'$folderId' in parents and trashed=false")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("fields", "nextPageToken,files($FIELDS)")
                .apply { pageTokenForList?.let { addQueryParameter("pageToken", it) } }
                .build().toString()
            val page = getJson(url)
            for (f in page["files"]?.jsonArray.orEmpty()) {
                val item = item(f.jsonObject)
                i.put(item)
                if (item.isDirectory) scan(i, item.id)
            }
            pageTokenForList = page["nextPageToken"]?.jsonPrimitive?.contentOrNull
        } while (pageTokenForList != null)
    }

    private fun item(o: JsonObject) = RemoteIndex.Item(
        id = o["id"]!!.jsonPrimitive.content,
        name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
        parentId = o["parents"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull,
        isDirectory = o["mimeType"]?.jsonPrimitive?.contentOrNull == FOLDER,
        version = o["version"]?.jsonPrimitive?.contentOrNull ?: "",
        size = o["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0,
        modified = o["modifiedTime"]?.jsonPrimitive?.contentOrNull ?: "",
    )

    private fun readItem(response: Response, what: String): JsonObject {
        if (!response.isSuccessful) fail(response, what)
        return json.parseToJsonElement(response.body.string()).jsonObject
    }

    private suspend fun getJson(url: String): JsonObject = call(Request.Builder().url(url).build()) { readItem(it, url) }

    /** Runs a request with the access token; after a 401 once more with a fresh token. */
    private suspend fun <T> call(request: Request, handle: suspend (Response) -> T): T = withContext(Dispatchers.IO) {
        var token = tokens.current()
        repeat(2) { attempt ->
            http.newCall(request.newBuilder().header("Authorization", "Bearer $token").build()).execute().use {
                if (it.code == 401 && attempt == 0) {
                    token = tokens.refresh()
                } else {
                    if (it.code == 401) throw AuthenticationException(401)
                    return@withContext handle(it)
                }
            }
        }
        throw AuthenticationException(401)
    }

    private fun fail(response: Response, what: String): Nothing {
        val body = runCatching { response.body.string() }.getOrDefault("")
        // The user took back NibSync's access.
        if (response.code == 403 && ("insufficientPermissions" in body || "authError" in body)) throw AuthenticationException(403)
        throw RemoteException(response.code, "Google Drive ${response.code} for $what")
    }

    companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        val SCOPES = listOf("https://www.googleapis.com/auth/drive.file", "openid", "email")
        private const val FOLDER = "application/vnd.google-apps.folder"
        private const val FIELDS = "id,name,mimeType,parents,version,size,modifiedTime"
        private val OCTETS = "application/octet-stream".toMediaType()
        private val JSON = "application/json; charset=UTF-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
    }
}
