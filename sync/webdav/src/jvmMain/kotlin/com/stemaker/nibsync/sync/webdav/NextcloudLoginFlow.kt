package com.stemaker.nibsync.sync.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Credentials issued by Nextcloud for this app (an app password, revocable in Nextcloud). */
@Serializable
data class NextcloudAccount(
    @SerialName("server") val serverUrl: String,
    @SerialName("loginName") val loginName: String,
    @SerialName("appPassword") val appPassword: String,
)

/**
 * Nextcloud Login Flow v2: [start] returns a page to open in the browser; while the user logs
 * in there, [poll] returns null; afterwards it returns the account with a fresh app password.
 */
class NextcloudLoginFlow(
    private val client: OkHttpClient = OkHttpClient(),
    /** Shown in Nextcloud's list of devices/app passwords. */
    private val appName: String = "NibSync",
) {
    data class Started(val loginUrl: String, val pollEndpoint: String, val token: String)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun start(serverUrl: String): Started = withContext(Dispatchers.IO) {
        val url = normalizeServerUrl(serverUrl).toHttpUrl().newBuilder().addPathSegments("index.php/login/v2").build()
        val request = Request.Builder().url(url).header("User-Agent", appName).post(ByteArray(0).toRequestBody()).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RemoteException(response.code, "Login start failed: HTTP ${response.code}")
            val body = json.decodeFromString(StartResponse.serializer(), response.body.string())
            Started(body.login, body.poll.endpoint, body.poll.token)
        }
    }

    /** One poll; null while the user has not finished logging in. */
    suspend fun poll(started: Started): NextcloudAccount? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(started.pollEndpoint).header("User-Agent", appName)
            .post(FormBody.Builder().add("token", started.token).build()).build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                404 -> null
                200 -> json.decodeFromString(PollResponse.serializer(), response.body.string())
                    .let { NextcloudAccount(it.server.trimEnd('/'), it.loginName, it.appPassword) }
                else -> throw RemoteException(response.code, "Login poll failed: HTTP ${response.code}")
            }
        }
    }

    companion object {
        /** Accepts "cloud.example.org" as well as full URLs; https is assumed when missing. */
        fun normalizeServerUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        }
    }

    @Serializable
    private data class StartResponse(val poll: Poll, val login: String)

    @Serializable
    private data class Poll(val token: String, val endpoint: String)

    @Serializable
    private data class PollResponse(val server: String, val loginName: String, val appPassword: String)
}
