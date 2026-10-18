package com.stemaker.nibsync.desktop

import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.sync.client.CloudClients
import com.stemaker.nibsync.sync.client.GoogleDriveConfig
import com.stemaker.nibsync.sync.client.OneDriveConfig
import com.stemaker.nibsync.sync.client.SyncService
import com.stemaker.nibsync.sync.cloud.OAuthEndpoints
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import kotlin.concurrent.thread

class DesktopCloudLoginTest {
    private val tokenServer = MockWebServer()
    private lateinit var endpoints: OAuthEndpoints

    @Before
    fun start() {
        tokenServer.start()
        endpoints = OAuthEndpoints(tokenServer.url("/authorize").toString(), tokenServer.url("/token").toString())
    }

    @After
    fun stop() = tokenServer.close()

    /** Plays the browser: the user logs in, the provider redirects to the app with a code. */
    private var page = ""
    private val browser: (String) -> Boolean = { url ->
        val u = url.toHttpUrl()
        thread {
            val back = URI("${u.queryParameter("redirect_uri")}/?code=the-code&state=${u.queryParameter("state")}").toURL().openConnection() as HttpURLConnection
            page = back.inputStream.bufferedReader().readText()
        }
        true
    }

    private fun answer(email: String) {
        val id = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString("""{"email":"$email"}""".toByteArray()) + ".s"
        tokenServer.enqueue(MockResponse(200, Headers.headersOf("Content-Type", "application/json"), """{"access_token":"a","refresh_token":"r","expires_in":3600,"id_token":"$id"}"""))
    }

    @Test
    fun oneDriveLoginThroughTheLoopbackAddress() = runBlocking<Unit> {
        answer("user@outlook.example.com")
        val config = DesktopCloudLogin.run(SyncService.ONEDRIVE, CloudClients.oneDriveOAuth("ms-client", endpoints), browser, Strings(german = true))
        assertThat(config).isEqualTo(OneDriveConfig("user@outlook.example.com", "r"))
        val sent = tokenServer.takeRequest().body!!.utf8()
        assertThat(sent).contains("redirect_uri=http%3A%2F%2Flocalhost%3A")
        assertThat(page).contains("Anmeldung abgeschlossen")
    }

    @Test
    fun googleLoginUsesTheLoopbackIp() = runBlocking<Unit> {
        answer("user@gmail.example.com")
        val config = DesktopCloudLogin.run(SyncService.GOOGLE_DRIVE, CloudClients.googleOAuth("g-client", "g-secret", endpoints), browser, Strings(german = true))
        assertThat(config).isEqualTo(GoogleDriveConfig("user@gmail.example.com", "r"))
        val sent = tokenServer.takeRequest().body!!.utf8()
        assertThat(sent).contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A")
        assertThat(sent).contains("client_secret=g-secret")
    }
}
