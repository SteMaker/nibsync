package com.stemaker.nibsync.app.sync

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.stemaker.nibsync.app.MainActivity
import com.stemaker.nibsync.sync.client.BrowserLogin
import com.stemaker.nibsync.sync.client.CloudClients
import com.stemaker.nibsync.sync.client.GoogleDriveConfig
import com.stemaker.nibsync.sync.client.SyncConfig
import com.stemaker.nibsync.sync.cloud.AccessTokens
import com.stemaker.nibsync.sync.webdav.AuthenticationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Answers of browser logins (the app's redirect URI), handed from [OAuthRedirectActivity] to the waiting login. */
object OAuthRedirects {
    val answers = MutableSharedFlow<String>(extraBufferCapacity = 1)
}

/** Receives the redirect of a browser login, passes it on and brings the app back. */
class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.dataString?.let { OAuthRedirects.answers.tryEmit(it) }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}

/** OneDrive login in the browser; the answer comes back through [OAuthRedirectActivity]. */
suspend fun oneDriveLogin(context: Context, clients: CloudClients): SyncConfig {
    val oauth = clients.oneDrive ?: error("OneDrive is not set up")
    val login = BrowserLogin(com.stemaker.nibsync.sync.client.SyncService.ONEDRIVE, oauth, REDIRECT_URI)
    context.startActivity(Intent(Intent.ACTION_VIEW, login.url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    val answer = withTimeout(10 * 60_000L) { OAuthRedirects.answers.first() }
    return login.complete(answer)
}

const val REDIRECT_URI = "com.stemaker.nibsync://oauth"

private val driveFileScope = Scope("https://www.googleapis.com/auth/drive.file")

/** Access to Drive, for [email] or (null) an account the user picks. */
private fun googleRequest(email: String?) = AuthorizationRequest.builder()
    .setRequestedScopes(listOf(driveFileScope))
    .apply { if (email != null) setAccount(android.accounts.Account(email, "com.google")) }
    .build()

/** Asks Google for access; the result may need the user's consent first (it has a resolution). */
suspend fun googleAuthorize(context: Context, email: String? = null): AuthorizationResult = suspendCancellableCoroutine { cont ->
    Identity.getAuthorizationClient(context).authorize(googleRequest(email))
        .addOnSuccessListener { cont.resume(it) }
        .addOnFailureListener { cont.resumeWithException(it) }
}

/** The Google account behind an access token, for display. */
suspend fun googleAccount(accessToken: String): String = withContext(Dispatchers.IO) {
    val request = Request.Builder().url("https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)").header("Authorization", "Bearer $accessToken").build()
    OkHttpClient().newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@withContext "?"
        Json.parseToJsonElement(response.body.string()).jsonObject["user"]?.jsonObject?.get("emailAddress")?.jsonPrimitive?.content ?: "?"
    }
}

/**
 * Whether Google refused because no Android OAuth client matches this app's package and signing
 * certificate: DEVELOPER_ERROR, or (after choosing the account) "UNREGISTERED_ON_API_CONSOLE".
 */
fun isUnregisteredApp(statusCode: Int, message: String?): Boolean =
    statusCode == com.google.android.gms.common.api.CommonStatusCodes.DEVELOPER_ERROR ||
        message.orEmpty().contains("UNREGISTERED_ON_API_CONSOLE")

/** SHA-1 of this app's signing certificate as the Google Cloud console expects it (AA:BB:...). */
fun signingSha1(context: Context): String {
    val pm = context.packageManager
    val cert = if (android.os.Build.VERSION.SDK_INT >= 28) {
        pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
    } ?: return "?"
    return java.security.MessageDigest.getInstance("SHA-1").digest(cert.toByteArray()).joinToString(":") { "%02X".format(it) }
}

/** Configuration after the user granted access (Google handles the tokens on the device). */
suspend fun googleConfig(accessToken: String): SyncConfig = GoogleDriveConfig(googleAccount(accessToken), refreshToken = "")

/**
 * Google Drive access tokens of the Google account [email] on Android, from Google's sign-in on
 * the device (each account separately, e.g. private and work): no refresh token
 * is stored, Google renews access silently while it stays granted. If the user takes access
 * back, sync reports an invalid login and the user connects again.
 */
class GoogleDeviceTokens(private val context: Context, private val email: String) : AccessTokens {
    private val lock = Mutex()
    private var token: String? = null
    private var fetchedAt = 0L

    override suspend fun current(): String = lock.withLock {
        token?.takeIf { System.currentTimeMillis() - fetchedAt < MAX_AGE_MS } ?: fetch()
    }

    override suspend fun refresh(): String = lock.withLock { fetch() }

    private suspend fun fetch(): String {
        val result = googleAuthorize(context, email)
        if (result.hasResolution()) throw AuthenticationException(401)
        return (result.accessToken ?: throw AuthenticationException(401)).also {
            token = it
            fetchedAt = System.currentTimeMillis()
        }
    }

    private companion object {
        /** Google access tokens live an hour; ask again a little earlier. */
        const val MAX_AGE_MS = 45 * 60_000L
    }
}
