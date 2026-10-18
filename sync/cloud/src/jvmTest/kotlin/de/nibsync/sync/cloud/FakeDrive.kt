package de.nibsync.sync.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import okhttp3.MultipartReader
import okio.Buffer

/** In-memory stand-in for the part of the Drive API v3 the Google Drive store uses. Token "good" only. */
class FakeDrive : Dispatcher() {
    class File(val id: String, var name: String, var parent: String?, val isFolder: Boolean, var bytes: ByteArray = ByteArray(0), var version: Long = 1, var seq: Int = 0, val created: String)

    val server = MockWebServer()
    val files = LinkedHashMap<String, File>()
    private val removed = ArrayList<Pair<Int, String>>()
    private var seq = 0
    private var nextId = 1
    var requests = 0

    init {
        server.dispatcher = this
    }

    val api get() = server.url("/drive/v3").toString().trimEnd('/')
    val upload get() = server.url("/upload/drive/v3").toString().trimEnd('/')
    fun start() = server.start()
    fun shutdown() = server.close()

    private fun touch(f: File) {
        f.seq = ++seq
        f.version++
    }

    fun create(name: String, parent: String, isFolder: Boolean, bytes: ByteArray = ByteArray(0)): File {
        val f = File("f${nextId++}", name, parent, isFolder, bytes, created = "2026-10-18T10:%02d:00Z".format(nextId))
        files[f.id] = f
        touch(f)
        return f
    }

    fun remove(id: String) {
        val f = files.remove(id) ?: return
        removed += ++seq to f.id
        files.values.filter { it.parent == id }.toList().forEach { remove(it.id) }
    }

    private fun json(f: File): JsonObject = buildJsonObject {
        put("id", f.id)
        put("name", f.name)
        put("mimeType", if (f.isFolder) "application/vnd.google-apps.folder" else "application/octet-stream")
        f.parent?.let { put("parents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(it)) }) }
        put("version", f.version.toString())
        if (!f.isFolder) put("size", f.bytes.size.toString())
        put("modifiedTime", "2026-10-18T11:%02d:%02dZ".format(f.seq / 60 % 60, f.seq % 60))
        put("createdTime", f.created)
    }

    private fun ok(o: JsonObject) = MockResponse(200, Headers.headersOf("Content-Type", "application/json"), o.toString())
    private fun bytes(code: Int, b: ByteArray) = MockResponse.Builder().code(code).body(Buffer().write(b)).build()
    private val empty = Headers.headersOf()

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests++
        if (request.headers["Authorization"] != "Bearer good") return MockResponse(401, empty, "")
        val url = request.url
        val path = url.encodedPath
        val m = request.method
        return when {
            m == "GET" && path == "/drive/v3/changes/startPageToken" -> ok(buildJsonObject { put("startPageToken", seq.toString()) })
            m == "GET" && path == "/drive/v3/changes" -> {
                val since = url.queryParameter("pageToken")!!.toInt()
                val changed = files.values.filter { it.seq > since }
                val gone = removed.filter { it.first > since }.map { it.second }
                ok(buildJsonObject {
                    put("changes", buildJsonArray {
                        changed.forEach { add(buildJsonObject { put("fileId", it.id); put("removed", false); put("file", json(it)) }) }
                        gone.forEach { add(buildJsonObject { put("fileId", it); put("removed", true) }) }
                    })
                    put("newStartPageToken", seq.toString())
                })
            }
            m == "GET" && path == "/drive/v3/files" -> {
                val q = url.queryParameter("q")!!
                val result = Regex("name='([^']*)'.*'root' in parents").find(q)?.let { match ->
                    files.values.filter { it.parent == "root" && it.isFolder && it.name == match.groupValues[1] }
                } ?: Regex("'([^']*)' in parents").find(q)!!.groupValues[1].let { parent -> files.values.filter { it.parent == parent } }
                ok(buildJsonObject { put("files", buildJsonArray { result.forEach { add(json(it)) } }) })
            }
            m == "POST" && path == "/drive/v3/files" -> {
                val meta = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                ok(json(create(meta["name"]!!.jsonPrimitive.content, meta["parents"]!!.jsonArray[0].jsonPrimitive.content, isFolder = true)))
            }
            m == "GET" && path.startsWith("/drive/v3/files/") && url.queryParameter("alt") == "media" -> {
                val f = files[path.removePrefix("/drive/v3/files/")] ?: return MockResponse(404, empty, "")
                val range = request.headers["Range"] ?: return bytes(200, f.bytes)
                val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                if (from >= f.bytes.size) MockResponse(416, empty, "") else bytes(206, f.bytes.copyOfRange(from, f.bytes.size))
            }
            m == "PATCH" && path.startsWith("/upload/drive/v3/files/") -> {
                val f = files[path.removePrefix("/upload/drive/v3/files/")] ?: return MockResponse(404, empty, "")
                f.bytes = request.body!!.toByteArray()
                touch(f)
                ok(json(f))
            }
            m == "POST" && path == "/upload/drive/v3/files" -> {
                val boundary = request.headers["Content-Type"]!!.substringAfter("boundary=")
                val reader = MultipartReader(Buffer().write(request.body!!), boundary)
                val meta = Json.parseToJsonElement(reader.nextPart()!!.body.readUtf8()).jsonObject
                val content = reader.nextPart()!!.body.readByteArray()
                ok(json(create(meta["name"]!!.jsonPrimitive.content, meta["parents"]!!.jsonArray[0].jsonPrimitive.content, isFolder = false, bytes = content)))
            }
            m == "DELETE" && path.startsWith("/drive/v3/files/") -> {
                val id = path.removePrefix("/drive/v3/files/")
                if (id !in files) return MockResponse(404, empty, "")
                remove(id)
                MockResponse(204, empty, "")
            }
            else -> MockResponse(500, empty, "unexpected $m $path")
        }
    }
}
