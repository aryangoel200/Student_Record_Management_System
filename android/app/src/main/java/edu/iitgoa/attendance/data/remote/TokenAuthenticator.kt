package edu.iitgoa.attendance.data.remote

import edu.iitgoa.attendance.data.local.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Refreshes the access token when the server answers 401.
 *
 * OkHttp calls this at most once per response chain and retries the original
 * request with whatever we return, so this is the native equivalent of the web
 * client's axios response interceptor.
 */
class TokenAuthenticator(
    private val tokenStore: TokenStore,
    private val refreshApi: RefreshApi,
    private val onSessionExpired: () -> Unit,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // Give up rather than looping if the refreshed token is also rejected.
        if (response.priorResponseCount() >= 1) return null

        val staleToken = response.request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.trim()

        synchronized(this) {
            val current = runBlocking { tokenStore.accessToken() }

            // Another request on a different thread may have already refreshed
            // while this one was queued. If so, just reuse the new token.
            if (current != null && current != staleToken) {
                return response.request.retryWith(current)
            }

            val refresh = runBlocking { tokenStore.refreshToken() } ?: return null

            val fresh = runCatching {
                runBlocking { refreshApi.refresh(RefreshRequest(refresh)) }
            }.getOrElse {
                // Refresh token expired or blacklisted — the session is over.
                runBlocking { tokenStore.clear() }
                onSessionExpired()
                return null
            }

            runBlocking { tokenStore.save(fresh.access, fresh.refresh) }
            return response.request.retryWith(fresh.access)
        }
    }

    private fun Request.retryWith(token: String): Request =
        newBuilder().header("Authorization", "Bearer $token").build()

    private fun Response.priorResponseCount(): Int {
        var count = 0
        var prior = priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
