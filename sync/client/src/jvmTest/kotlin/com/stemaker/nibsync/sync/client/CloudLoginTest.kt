package com.stemaker.nibsync.sync.client

import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.sync.cloud.OAuthClient
import com.stemaker.nibsync.sync.cloud.OAuthEndpoints
import com.stemaker.nibsync.sync.cloud.RefreshingTokens
import com.stemaker.nibsync.sync.webdav.AuthenticationException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Base64
import kotlin.test.assertFailsWith

class CloudLoginTest {
    private val server = MockWebServer()
    private lateinit var endpoints: OAuthEndpoints

    @Before
    fun start() {
        server.start()
        endpoints = OAuthEndpoints(server.url("/authorize").toString(), server.url("/token").toString())
    }

    @After
    fun stop() = server.close()

    private fun idToken(email: String): String {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"email":"$email"}""".toByteArray())
        return "e30.$payload.sig"
    }

    private fun tokenAnswer(refresh: String? = "rt-1", email: String = "user@outlook.example.com") = MockResponse(
        200, Headers.headersOf("Content-Type", "application/json"),
        """{"access_token":"at-1","expires_in":3600${refresh?.let { ",\"refresh_token\":\"$it\"" } ?: ""},"id_token":"${idToken(email)}"}""",
    )

    @Test
    fun browserLoginBecomesAStoredLogin() = runBlocking<Unit> {
        val login = BrowserLogin(SyncService.ONEDRIVE, CloudClients.oneDriveOAuth("client-1", endpoints), "http://localhost:4711")
        val url = login.url.toHttpUrl()
        assertThat(url.queryParameter("client_id")).isEqualTo("client-1")
        assertThat(url.queryParameter("code_challenge_method")).isEqualTo("S256")
        assertThat(url.queryParameter("scope")).contains("Files.ReadWrite.AppFolder")
        server.enqueue(tokenAnswer())
        val config = login.complete("http://localhost:4711/?code=c-1&state=${url.queryParameter("state")}")
        assertThat(config).isEqualTo(OneDriveConfig("user@outlook.example.com", "rt-1"))
        val sent = server.takeRequest().body!!.utf8()
        assertThat(sent).contains("code_verifier=")
        assertThat(sent).contains("code=c-1")
    }

    @Test
    fun androidRedirectsWithTheAppSchemeWork() = runBlocking<Unit> {
        val login = BrowserLogin(SyncService.ONEDRIVE, CloudClients.oneDriveOAuth("client-1", endpoints), "com.stemaker.nibsync://oauth")
        server.enqueue(tokenAnswer())
        val state = login.url.toHttpUrl().queryParameter("state")
        assertThat(login.complete("com.stemaker.nibsync://oauth?code=c-2&state=$state")).isInstanceOf(OneDriveConfig::class.java)
    }

    @Test
    fun foreignOrFailedAnswersAreRefused() = runBlocking<Unit> {
        val login = BrowserLogin(SyncService.GOOGLE_DRIVE, CloudClients.googleOAuth("g-1", "s", endpoints), "http://127.0.0.1:4711")
        assertThat(login.url.toHttpUrl().queryParameter("access_type")).isEqualTo("offline")
        assertFailsWith<BrowserLogin.LoginFailed> { login.complete("http://127.0.0.1:4711/?code=x&state=someone-else") }
        assertFailsWith<BrowserLogin.LoginFailed> { login.complete("http://127.0.0.1:4711/?error=access_denied") }
    }

    @Test
    fun refreshKeepsRotatedTokensAndRevokedLoginsAreReported() = runBlocking<Unit> {
        val saved = mutableListOf<String>()
        val oauth = OAuthClient(endpoints, "client-1", listOf("x"))
        val tokens = RefreshingTokens(oauth, "rt-1", onRefreshToken = { saved += it })
        server.enqueue(tokenAnswer(refresh = "rt-2"))
        assertThat(tokens.current()).isEqualTo("at-1")
        assertThat(saved).containsExactly("rt-2")
        // Still valid: no new request.
        assertThat(tokens.current()).isEqualTo("at-1")
        assertThat(server.requestCount).isEqualTo(1)
        server.enqueue(MockResponse(400, Headers.headersOf(), """{"error":"invalid_grant"}"""))
        assertFailsWith<AuthenticationException> { tokens.refresh() }
    }

    @Test
    fun servicesAreAvailableOnlyWithTheirClients() {
        val none = CloudClients()
        assertThat(none.oneDrive).isNull()
        assertThat(none.googleDesktop).isNull()
        assertThat(CloudClients(oneDriveClientId = "id").oneDrive).isNotNull()
        assertThat(CloudClients(googleDesktopClientId = " ").googleDesktop).isNull()
    }

    @Test
    fun clientsEnteredByTheUserOverrideTheBuiltInOnes() {
        val built = CloudClients(oneDriveClientId = "built-ms", googleDesktopClientId = "built-g", googleDesktopSecret = "built-s")
        val user = CloudClients(oneDriveClientId = " user-ms ", googleDesktopClientId = "")
        val merged = built.overriddenBy(user)
        assertThat(merged.oneDriveClientId).isEqualTo("user-ms")
        assertThat(merged.googleDesktopClientId).isEqualTo("built-g")
        assertThat(merged.googleDesktopSecret).isEqualTo("built-s")
        assertThat(CloudClients.fromMap(user.toMap())).isEqualTo(user)
    }
}
