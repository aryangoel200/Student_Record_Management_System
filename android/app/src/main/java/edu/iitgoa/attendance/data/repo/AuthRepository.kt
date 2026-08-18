package edu.iitgoa.attendance.data.repo

import edu.iitgoa.attendance.data.ApiResult
import edu.iitgoa.attendance.data.CurrentUser
import edu.iitgoa.attendance.data.SessionManager
import edu.iitgoa.attendance.data.apiCall
import edu.iitgoa.attendance.data.orThrow
import edu.iitgoa.attendance.data.local.TokenStore
import edu.iitgoa.attendance.data.remote.AttendanceApi
import edu.iitgoa.attendance.data.remote.LoginRequest
import edu.iitgoa.attendance.data.remote.LogoutRequest
import edu.iitgoa.attendance.data.remote.RegisterRequest
import edu.iitgoa.attendance.data.toCurrentUser

class AuthRepository(
    private val api: AttendanceApi,
    private val tokenStore: TokenStore,
    private val session: SessionManager,
) {

    suspend fun login(username: String, password: String): ApiResult<CurrentUser> =
        apiCall { api.login(LoginRequest(username.trim(), password)) }
            .also { result ->
                if (result is ApiResult.Ok) {
                    tokenStore.save(result.value.token.access, result.value.token.refresh)
                    session.onSignedIn(result.value.user.toCurrentUser())
                }
            }
            .map { it.user.toCurrentUser() }

    suspend fun register(
        username: String,
        name: String,
        email: String,
        password: String,
        confirmPassword: String,
    ): ApiResult<CurrentUser> =
        apiCall {
            api.register(
                RegisterRequest(
                    username = username.trim(),
                    name = name.trim(),
                    email = email.trim(),
                    password = password,
                    password2 = confirmPassword,
                )
            )
        }
            .also { result ->
                if (result is ApiResult.Ok) {
                    tokenStore.save(result.value.token.access, result.value.token.refresh)
                    session.onSignedIn(result.value.user.toCurrentUser())
                }
            }
            .map { it.user.toCurrentUser() }

    /** Reloads the profile, e.g. after face enrolment flips `face_enrolled`. */
    suspend fun refreshProfile(): ApiResult<CurrentUser> =
        apiCall { api.profile() }
            .map { it.toCurrentUser() }
            .also { if (it is ApiResult.Ok) session.onSignedIn(it.value) }

    /** Restores the session on cold start, if a token is still stored. */
    suspend fun restoreSession(): CurrentUser? {
        if (tokenStore.accessToken() == null) {
            session.onSignedOut()
            return null
        }
        return when (val result = refreshProfile()) {
            is ApiResult.Ok -> result.value
            is ApiResult.Err -> {
                tokenStore.clear()
                session.onSignedOut()
                null
            }
        }
    }

    suspend fun logout() {
        // Blacklist the refresh token server-side so it cannot be replayed.
        // Best effort — a failure must not leave the user stuck signed in.
        tokenStore.refreshToken()?.let { refresh ->
            apiCall { api.logout(LogoutRequest(refresh)).orThrow() }
        }
        tokenStore.clear()
        session.onSignedOut()
    }
}

private inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Ok -> ApiResult.Ok(transform(value))
    is ApiResult.Err -> this
}
