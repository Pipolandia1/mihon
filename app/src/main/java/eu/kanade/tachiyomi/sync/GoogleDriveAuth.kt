package eu.kanade.tachiyomi.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.suspendCancellableCoroutine
import tachiyomi.data.sync.DriveFiles
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Access to the app's hidden Drive folder through Google Play services (docs/DESKTOP_PLAN.md §3.4).
 *
 * Google recognises the app by its package and the fork's signing key, registered once in the
 * reader's own Google Cloud project (docs/SYNC_GOOGLE_SETUP.md): there is no client id in the code.
 * Play services keep the grant and hand out fresh tokens; only the first time, or after the reader
 * revokes it, does Google need to show its consent screen, and only the Sync screen can show it.
 */
@Inject
@SingleIn(AppScope::class)
class GoogleDriveAuth(private val context: Context) {

    sealed interface Grant {
        data class Token(val value: String) : Grant

        /** Google needs the reader: the Sync screen launches this and hands the result to [tokenFrom]. */
        data class NeedsConsent(val intent: PendingIntent) : Grant
    }

    private val client get() = Identity.getAuthorizationClient(context)

    private val scopes = listOf(Scope(DriveFiles.SCOPE))

    suspend fun authorize(): Grant {
        val result = client.authorize(AuthorizationRequest.builder().setRequestedScopes(scopes).build()).await()
        val intent = result.pendingIntent
        return when {
            result.hasResolution() && intent != null -> Grant.NeedsConsent(intent)
            else -> Grant.Token(checkNotNull(result.accessToken) { "Google granted access without a token" })
        }
    }

    /** A token without asking the reader anything, or null when Google needs them. */
    suspend fun token(): String? = (authorize() as? Grant.Token)?.value

    /** The token from the consent screen's result. */
    fun tokenFrom(data: Intent?): String? = client.getAuthorizationResultFromIntent(data).accessToken

    /** Drops a token Drive refused, so the next one is fresh. */
    suspend fun clear(token: String) {
        client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
    }

    /** Withdraws the app's access to Drive, for when the reader disconnects. */
    suspend fun revoke() {
        client.revokeAccess(RevokeAccessRequest.builder().setScopes(scopes).build()).await()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
