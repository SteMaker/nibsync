package com.stemaker.nibsync.sync.webdav

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith

class WebDavRemoteStoreTest {
    private val server = MockWebServer()
    private lateinit var store: WebDavRemoteStore

    @Before
    fun start() {
        server.start()
        store = WebDavRemoteStore(server.url("/nc").toString(), "user@example.org", "secret", "My Notes/NibSync")
    }

    @After
    fun stop() = server.close()

    private fun respond(code: Int, body: String = "") =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private val listing = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns">
 <d:response><d:href>/nc/remote.php/webdav/My%20Notes/NibSync/documents/</d:href>
  <d:propstat><d:prop><d:getetag>&quot;root-etag&quot;</d:getetag><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  <d:propstat><d:prop><d:getcontentlength/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>
 <d:response><d:href>/nc/remote.php/webdav/My%20Notes/NibSync/documents/abc-123/</d:href>
  <d:propstat><d:prop><d:getetag>&quot;dir-etag&quot;</d:getetag><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/nc/remote.php/webdav/My%20Notes/NibSync/documents/%C3%BCber.json</d:href>
  <d:propstat><d:prop><d:getetag>&quot;file-etag&quot;</d:getetag><d:getcontentlength>42</d:getcontentlength><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
</d:multistatus>"""

    @Test
    fun listParsesFoldersFilesAndEtags() = runTest {
        respond(207, listing)
        val result = store.list("documents")!!
        assertThat(result.etag).isEqualTo("root-etag")
        assertThat(result.entries.map { Triple(it.name, it.isDirectory, it.size) })
            .containsExactly(Triple("abc-123", true, 0L), Triple("über.json", false, 42L))
        assertThat(result["über.json"]!!.etag).isEqualTo("file-etag")

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PROPFIND")
        assertThat(request.headers["Depth"]).isEqualTo("1")
        assertThat(request.url.encodedPath).isEqualTo("/nc/remote.php/webdav/My%20Notes/NibSync/documents")
        assertThat(request.headers["Authorization"]).startsWith("Basic ")
    }

    @Test
    fun missingFolderIsNull() = runTest {
        respond(404)
        assertThat(store.list("nope")).isNull()
    }

    @Test
    fun readFromOffsetUsesRange() = runTest {
        respond(206, "world")
        assertThat(store.read("a/b.oplog", 6).decodeToString()).isEqualTo("world")
        assertThat(server.takeRequest().headers["Range"]).isEqualTo("bytes=6-")
    }

    @Test
    fun serverIgnoringRangeIsHandled() = runTest {
        respond(200, "hello world")
        assertThat(store.read("a", 6).decodeToString()).isEqualTo("world")
    }

    @Test
    fun readBeyondEndGivesNothing() = runTest {
        respond(416)
        assertThat(store.read("a", 100)).isEmpty()
    }

    @Test
    fun writeIsPut() = runTest {
        respond(201)
        store.write("library/x.json", "{}".encodeToByteArray())
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.body!!.utf8()).isEqualTo("{}")
    }

    @Test
    fun existingFolderIsFine() = runTest {
        respond(405)
        store.makeDirectory("documents")
        assertThat(server.takeRequest().method).isEqualTo("MKCOL")
    }

    @Test
    fun deletingMissingIsFine() = runTest {
        respond(404)
        store.delete("documents/x")
    }

    @Test
    fun revokedLoginIsReported() = runTest {
        respond(401)
        assertFailsWith<AuthenticationException> { store.list("") }
    }

    @Test
    fun serverErrorsAreReported() = runTest {
        respond(503)
        val e = assertFailsWith<RemoteException> { store.write("a", ByteArray(1)) }
        assertThat(e.status).isEqualTo(503)
    }
}

class NextcloudLoginFlowTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    @Test
    fun startPollAndCredentials() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"poll":{"token":"tok","endpoint":"${server.url("/login/v2/poll")}"},"login":"https://cloud/login/v2/flow/abc"}""").build())
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"server":"https://cloud.example.org/","loginName":"stefan","appPassword":"app-pw"}""").build())
        val flow = NextcloudLoginFlow()
        val started = flow.start(server.url("/").toString())
        assertThat(started.loginUrl).isEqualTo("https://cloud/login/v2/flow/abc")
        assertThat(flow.poll(started)).isNull()
        assertThat(flow.poll(started)).isEqualTo(NextcloudAccount("https://cloud.example.org", "stefan", "app-pw"))
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/index.php/login/v2")
        assertThat(server.takeRequest().body!!.utf8()).isEqualTo("token=tok")
    }

    @Test
    fun serverUrlIsNormalized() {
        assertThat(NextcloudLoginFlow.normalizeServerUrl(" cloud.example.org/ ")).isEqualTo("https://cloud.example.org")
        assertThat(NextcloudLoginFlow.normalizeServerUrl("http://192.168.4.100:8089")).isEqualTo("http://192.168.4.100:8089")
    }
}
