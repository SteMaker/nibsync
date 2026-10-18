package de.nibsync.sync.cloud

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import java.net.URLDecoder

/**
 * In-memory stand-in for the part of Microsoft Graph the OneDrive store uses: the app folder,
 * delta, children, uploads, ranged downloads, folder creation and deletion. Accepts only the
 * access token "good".
 */
class FakeGraph : Dispatcher() {
    class Item(val id: String, var name: String, var parentId: String?, val isDir: Boolean, var bytes: ByteArray = ByteArray(0), var version: Int = 1, var seq: Int = 0)

    val server = MockWebServer()
    val items = LinkedHashMap<String, Item>()
    private val deleted = ArrayList<Pair<Int, Item>>()
    private var seq = 0
    private var nextId = 1
    var deltaSupported = true
    var requests = 0

    init {
        items["root"] = Item("root", "NibSync", null, true)
        server.dispatcher = this
    }

    val base: String get() = server.url("/v1.0").toString().trimEnd('/')

    fun start() = server.start()
    fun shutdown() = server.close()

    private fun touch(item: Item) {
        item.seq = ++seq
        item.version++
    }

    private fun json(o: Item): JsonObject = buildJsonObject {
        put("id", o.id)
        put("name", o.name)
        o.parentId?.let { putJsonObject("parentReference") { put("id", it) } }
        if (o.isDir) putJsonObject("folder") {} else putJsonObject("file") {}
        put("size", o.bytes.size)
        put("cTag", "c-${o.id}-${o.version}")
        put("lastModifiedDateTime", "2026-10-18T10:00:%02dZ".format(o.seq % 60))
    }

    private fun ok(body: String, code: Int = 200) = MockResponse(code, Headers.headersOf("Content-Type", "application/json"), body)

    private fun bytes(code: Int, b: ByteArray) = MockResponse.Builder().code(code).body(okio.Buffer().write(b)).build()

    private fun descendants(id: String): List<Item> = items.values.filter { it.parentId == id }.flatMap { listOf(it) + descendants(it.id) }

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests++
        if (request.headers["Authorization"] != "Bearer good") return MockResponse(401, Headers.headersOf(), "")
        val path = request.url.encodedPath.removePrefix("/v1.0")
        val method = request.method
        val decoded = URLDecoder.decode(path, "UTF-8")
        return when {
            method == "GET" && path == "/me/drive/special/approot" -> ok(json(items.getValue("root")).toString())
            method == "GET" && path == "/me/drive/special/approot/delta" -> {
                if (!deltaSupported) return MockResponse(400, Headers.headersOf(), "")
                val since = request.url.queryParameter("token")?.toInt() ?: -1
                val changed = items.values.filter { since < 0 || it.seq > since }
                val gone = deleted.filter { since >= 0 && it.first > since }.map { it.second }
                val body = buildJsonObject {
                    put("value", buildJsonArray {
                        changed.forEach { add(json(it)) }
                        gone.forEach { add(buildJsonObject { put("id", it.id); putJsonObject("deleted") {} }) }
                    })
                    put("@odata.deltaLink", "$base/me/drive/special/approot/delta?token=$seq")
                }
                ok(body.toString())
            }
            method == "GET" && path.endsWith("/children") -> {
                val id = path.removePrefix("/me/drive/items/").removeSuffix("/children")
                ok(buildJsonObject { put("value", buildJsonArray { items.values.filter { it.parentId == id }.forEach { add(json(it)) } }) }.toString())
            }
            method == "GET" && path.endsWith("/content") -> {
                val item = items[path.removePrefix("/me/drive/items/").removeSuffix("/content")] ?: return MockResponse(404, Headers.headersOf(), "")
                val range = request.headers["Range"]
                if (range == null) return bytes(200, item.bytes)
                val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                if (from >= item.bytes.size) return MockResponse(416, Headers.headersOf(), "")
                bytes(206, item.bytes.copyOfRange(from, item.bytes.size))
            }
            method == "PUT" && decoded.endsWith(":/content") -> {
                val (parent, name) = decoded.removePrefix("/me/drive/items/").removeSuffix(":/content").split(":/", limit = 2)
                val existing = items.values.firstOrNull { it.parentId == parent && it.name == name && !it.isDir }
                val item = existing ?: Item("i${nextId++}", name, parent, false).also { items[it.id] = it }
                item.bytes = request.body!!.toByteArray()
                touch(item)
                ok(json(item).toString(), if (existing == null) 201 else 200)
            }
            method == "POST" && path.endsWith("/children") -> {
                val parent = path.removePrefix("/me/drive/items/").removeSuffix("/children")
                val name = kotlinx.serialization.json.Json.parseToJsonElement(request.body!!.utf8()).jsonObject["name"]!!.jsonPrimitive.content
                if (items.values.any { it.parentId == parent && it.name == name }) return MockResponse(409, Headers.headersOf(), "")
                val item = Item("i${nextId++}", name, parent, true).also { items[it.id] = it }
                touch(item)
                ok(json(item).toString(), 201)
            }
            method == "GET" && decoded.contains(":/") -> {
                val (parent, name) = decoded.removePrefix("/me/drive/items/").split(":/", limit = 2)
                val item = items.values.firstOrNull { it.parentId == parent && it.name == name } ?: return MockResponse(404, Headers.headersOf(), "")
                ok(json(item).toString())
            }
            method == "DELETE" -> {
                val id = path.removePrefix("/me/drive/items/")
                val item = items[id] ?: return MockResponse(404, Headers.headersOf(), "")
                for (gone in descendants(id) + item) {
                    items.remove(gone.id)
                    deleted += ++seq to gone
                }
                MockResponse(204, Headers.headersOf(), "")
            }
            else -> MockResponse(500, Headers.headersOf(), "unexpected $method $path")
        }
    }
}
