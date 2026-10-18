package de.nibsync.sync.cloud

import de.nibsync.sync.webdav.AuthenticationException
import de.nibsync.sync.webdav.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Where a provider's OAuth 2.0 authorisation and token endpoints are. */
data class OAuthEndpoints(val authorizeUrl: String, val tokenUrl: String) {
    companion object {
        val MICROSOFT = OAuthEndpoints(
            "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
            "https://login.microsoftonline.com/common/oauth2/v2.0/token",
        )
        val GOOGLE = OAuthEndpoints("https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token")
    }
}

/** PKCE pair (RFC 7636): the verifier stays in the app, the challenge goes to the browser. */
class Pkce(val verifier: String) {
    val challenge: String = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    companion object {
        fun create(random: SecureRandom = SecureRandom()): Pkce = Pkce(base64Url(ByteArray(32).also(random::nextBytes)))
    }
}

/** Tokens from the token endpoint. [refreshToken] is null when the provider keeps the old one. */
data class Tokens(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Long, val idToken: String?)

/**
 * OAuth 2.0 for a public client (an app that cannot keep secrets): authorisation code with
 * PKCE, the code comes back to a loopback address (PC) or the app's own URI scheme (Android).
 * [clientSecret] is only for Google's desktop clients, where it is not secret by design.
 */
class OAuthClient(
    private val endpoints: OAuthEndpoints,
    val clientId: String,
    private val scopes: List<String>,
    private val clientSecret: String? = null,
    private val http: OkHttpClient = OkHttpClient(),
) {
    fun authorizationUrl(redirectUri: String, state: String, pkce: Pkce, extra: Map<String, String> = emptyMap()): String =
        endpoints.authorizeUrl.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", scopes.joinToString(" "))
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", pkce.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .apply { extra.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build().toString()

    suspend fun exchangeCode(code: String, redirectUri: String, pkce: Pkce): Tokens = post(
        "grant_type" to "authorization_code",
        "code" to code,
        "redirect_uri" to redirectUri,
        "code_verifier" to pkce.verifier,
    )

    /** New tokens for a refresh token; [AuthenticationException] if it is no longer valid. */
    suspend fun refresh(refreshToken: String): Tokens = post("grant_type" to "refresh_token", "refresh_token" to refreshToken)

    private suspend fun post(vararg params: Pair<String, String>): Tokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().add("client_id", clientId).apply {
            clientSecret?.let { add("client_secret", it) }
            if (params.none { it.first == "grant_type" && it.second == "authorization_code" }) add("scope", scopes.joinToString(" "))
            params.forEach { (k, v) -> add(k, v) }
        }.build()
        http.newCall(Request.Builder().url(endpoints.tokenUrl).post(form).build()).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                // invalid_grant: the refresh token was revoked or expired.
                if (response.code in 400..401 && "invalid_grant" in body) throw AuthenticationException(response.code)
                throw RemoteException(response.code, "Token request failed (${response.code}): ${body.take(200)}")
            }
            val t = json.decodeFromString(TokenResponse.serializer(), body)
            Tokens(t.accessToken, t.refreshToken, t.expiresIn ?: 3600, t.idToken)
        }
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
        @SerialName("id_token") val idToken: String? = null,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** A claim of an id token (e.g. "email"), without checking the signature: for display only. */
        fun idTokenClaim(idToken: String?, name: String): String? = runCatching {
            val payload = idToken!!.split('.')[1]
            val text = String(Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '=')))
            json.parseToJsonElement(text).jsonObject[name]?.jsonPrimitive?.content
        }.getOrNull()
    }
}

/** Gives the stores a valid access token; [refresh] gets a new one after a 401. */
interface AccessTokens {
    suspend fun current(): String
    suspend fun refresh(): String
}

/**
 * Access tokens from a refresh token, renewed shortly before they expire. Providers may hand
 * out a new refresh token with every refresh (Microsoft does); [onRefreshToken] stores it.
 */
class RefreshingTokens(
    private val client: OAuthClient,
    private var refreshToken: String,
    private val onRefreshToken: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : AccessTokens {
    private val lock = Mutex()
    private var access: String? = null
    private var expiresAt = 0L

    override suspend fun current(): String = lock.withLock {
        access?.takeIf { clock() < expiresAt - EARLY_MS } ?: renew()
    }

    override suspend fun refresh(): String = lock.withLock { renew() }

    private suspend fun renew(): String {
        val t = client.refresh(refreshToken)
        access = t.accessToken
        expiresAt = clock() + t.expiresInSeconds * 1000
        t.refreshToken?.takeIf { it != refreshToken }?.let {
            refreshToken = it
            onRefreshToken(it)
        }
        return t.accessToken
    }

    private companion object {
        const val EARLY_MS = 60_000L
    }
}

internal fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
