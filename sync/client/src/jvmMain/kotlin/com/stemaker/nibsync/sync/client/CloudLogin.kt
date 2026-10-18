package com.stemaker.nibsync.sync.client

import com.stemaker.nibsync.sync.RemoteStore
import com.stemaker.nibsync.sync.cloud.AccessTokens
import com.stemaker.nibsync.sync.cloud.GoogleDriveRemoteStore
import com.stemaker.nibsync.sync.cloud.OAuthClient
import com.stemaker.nibsync.sync.cloud.OAuthEndpoints
import com.stemaker.nibsync.sync.cloud.OneDriveRemoteStore
import com.stemaker.nibsync.sync.cloud.Pkce
import com.stemaker.nibsync.sync.cloud.RefreshingTokens
import com.stemaker.nibsync.sync.webdav.WebDavRemoteStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/**
 * The OAuth clients NibSync is registered with, once for the app (built in from
 * cloud.properties, see docs/cloud-setup.md). A null or blank id means that service is not set
 * up. On Android Google Drive needs no id in the app: Google recognises the app by package name
 * and signing certificate ([googleOnDevice]).
 */
data class CloudClients(
    val oneDriveClientId: String? = null,
    val googleDesktopClientId: String? = null,
    val googleDesktopSecret: String? = null,
    val googleOnDevice: Boolean = false,
) {
    val oneDrive: OAuthClient? get() = oneDriveClientId?.takeIf { it.isNotBlank() }?.let { oneDriveOAuth(it) }
    val googleDesktop: OAuthClient? get() = googleDesktopClientId?.takeIf { it.isNotBlank() }?.let { googleOAuth(it, googleDesktopSecret) }

    companion object {
        fun oneDriveOAuth(clientId: String, endpoints: OAuthEndpoints = OAuthEndpoints.MICROSOFT) =
            OAuthClient(endpoints, clientId, OneDriveRemoteStore.SCOPES)

        fun googleOAuth(clientId: String, secret: String?, endpoints: OAuthEndpoints = OAuthEndpoints.GOOGLE) =
            OAuthClient(endpoints, clientId, GoogleDriveRemoteStore.SCOPES, secret)
    }
}

/**
 * One login in the browser: open [url]; the provider then sends the browser to [redirectUri]
 * with a code, which [complete] turns into a stored login (the refresh token).
 */
class BrowserLogin(val service: SyncService, private val oauth: OAuthClient, val redirectUri: String) {
    private val pkce = Pkce.create()
    private val state = UUID.randomUUID().toString()

    init {
        require(service != SyncService.NEXTCLOUD) { "Nextcloud uses its own login flow" }
    }

    /** Let the user pick the account each time, and (Google) hand out a refresh token. */
    val url: String = oauth.authorizationUrl(
        redirectUri,
        state,
        pkce,
        if (service == SyncService.GOOGLE_DRIVE) mapOf("access_type" to "offline", "prompt" to "consent select_account") else mapOf("prompt" to "select_account"),
    )

    class LoginFailed(message: String) : Exception(message)

    /** [redirect] is the full address the browser was sent to. */
    suspend fun complete(redirect: String): SyncConfig {
        val uri = redirect.toHttpUrlOrNull() ?: redirect.replaceFirst(Regex("^[a-z.]+://"), "http://x/").toHttpUrlOrNull()
            ?: throw LoginFailed("Unexpected answer")
        uri.queryParameter("error")?.let { throw LoginFailed(uri.queryParameter("error_description") ?: it) }
        if (uri.queryParameter("state") != state) throw LoginFailed("Answer does not belong to this login")
        val code = uri.queryParameter("code") ?: throw LoginFailed("No code in the answer")
        val tokens = oauth.exchangeCode(code, redirectUri, pkce)
        val refresh = tokens.refreshToken ?: throw LoginFailed("No refresh token")
        val user = OAuthClient.idTokenClaim(tokens.idToken, "email")
            ?: OAuthClient.idTokenClaim(tokens.idToken, "preferred_username")
            ?: "?"
        return when (service) {
            SyncService.ONEDRIVE -> OneDriveConfig(user, refresh)
            SyncService.GOOGLE_DRIVE -> GoogleDriveConfig(user, refresh)
            SyncService.NEXTCLOUD -> error("unreachable")
        }
    }
}

/** The remote store for a sync configuration. */
object CloudStores {
    /**
     * [onLoginChanged] stores a configuration with a new refresh token (Microsoft hands out a
     * new one with each refresh). [googleTokens] replaces the refresh token on Android, where
     * Google's sign-in gives access tokens directly.
     */
    fun storeFor(
        config: SyncConfig,
        clients: CloudClients,
        googleTokens: AccessTokens? = null,
        onLoginChanged: (SyncConfig) -> Unit = {},
    ): RemoteStore = when (config) {
        is NextcloudConfig -> WebDavRemoteStore(config.account.serverUrl, config.account.loginName, config.account.appPassword, config.folder)
        is OneDriveConfig -> {
            val oauth = clients.oneDrive ?: error("OneDrive is not set up in this build")
            OneDriveRemoteStore(RefreshingTokens(oauth, config.refreshToken, onRefreshToken = { onLoginChanged(config.copy(refreshToken = it)) }))
        }
        is GoogleDriveConfig -> {
            val tokens = googleTokens ?: RefreshingTokens(
                clients.googleDesktop ?: error("Google Drive is not set up in this build"),
                config.refreshToken,
                onRefreshToken = { onLoginChanged(config.copy(refreshToken = it)) },
            )
            GoogleDriveRemoteStore(tokens)
        }
    }
}
