package de.nibsync.desktop

import com.sun.net.httpserver.HttpServer
import de.nibsync.sync.client.BrowserLogin
import de.nibsync.sync.cloud.OAuthClient
import de.nibsync.sync.client.CloudClients
import de.nibsync.sync.client.SyncConfig
import de.nibsync.sync.client.SyncService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Properties

/** The OAuth clients packed into this build (generated from cloud.properties). */
fun desktopCloudClients(): CloudClients {
    val p = Properties()
    DesktopCloudLogin::class.java.getResourceAsStream("/nibsync-cloud.properties")?.use { p.load(it) }
    return CloudClients(
        oneDriveClientId = p.getProperty("onedrive.clientId")?.trim(),
        googleDesktopClientId = p.getProperty("google.desktop.clientId")?.trim(),
        googleDesktopSecret = p.getProperty("google.desktop.clientSecret")?.trim(),
    )
}

/**
 * Login with OneDrive or Google Drive in the default browser: a small web server on the
 * loopback address receives the answer (OAuth for desktop apps, RFC 8252).
 */
object DesktopCloudLogin {
    const val TIMEOUT_MS = 10 * 60_000L

    suspend fun run(service: SyncService, clients: CloudClients, openBrowser: (String) -> Boolean, strings: Strings): SyncConfig {
        val oauth = when (service) {
            SyncService.ONEDRIVE -> clients.oneDrive
            SyncService.GOOGLE_DRIVE -> clients.googleDesktop
            SyncService.NEXTCLOUD -> null
        } ?: error("$service is not set up")
        return run(service, oauth, openBrowser, strings)
    }

    suspend fun run(service: SyncService, oauth: OAuthClient, openBrowser: (String) -> Boolean, strings: Strings): SyncConfig {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        try {
            // Microsoft registers "http://localhost" (any port), Google allows the loopback IP.
            val host = if (service == SyncService.ONEDRIVE) "localhost" else "127.0.0.1"
            val redirect = "http://$host:${server.address.port}"
            val login = BrowserLogin(service, oauth, redirect)
            val answer = CompletableDeferred<String>()
            server.createContext("/") { exchange ->
                val query = exchange.requestURI.rawQuery.orEmpty()
                val page = if ("code=" in query || "error=" in query) {
                    answer.complete(redirect + exchange.requestURI.toString())
                    strings.loginDonePage
                } else {
                    ""
                }
                val bytes = "<!doctype html><meta charset=utf-8><title>NibSync</title><p style='font-family:sans-serif'>$page</p>".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            if (!openBrowser(login.url)) throw BrowserLogin.LoginFailed(strings.syncOpenBrowser(login.url))
            return login.complete(withTimeout(TIMEOUT_MS) { answer.await() })
        } finally {
            server.stop(0)
        }
    }
}
