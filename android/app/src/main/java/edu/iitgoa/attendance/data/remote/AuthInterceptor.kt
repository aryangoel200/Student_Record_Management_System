package edu.iitgoa.attendance.data.remote

import edu.iitgoa.attendance.data.local.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/** Attaches the current access token to every outgoing request. */
class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Login and register must go out unauthenticated; sending a stale token
        // there would just produce a confusing 401.
        val path = request.url.encodedPath
        if (path.endsWith("/login") || path.endsWith("/register") ||
            path.endsWith("/token/refresh")
        ) {
            return chain.proceed(request)
        }

        // Blocking is fine here: OkHttp already runs interceptors off the main
        // thread, and reading one DataStore key is quick.
        val token = runBlocking { tokenStore.accessToken() }
            ?: return chain.proceed(request)

        return chain.proceed(
            request.newBuilder().header("Authorization", "Bearer $token").build()
        )
    }
}
