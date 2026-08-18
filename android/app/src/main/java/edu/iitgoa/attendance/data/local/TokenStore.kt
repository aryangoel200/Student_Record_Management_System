package edu.iitgoa.attendance.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "session")

/**
 * Where the JWT pair lives.
 *
 * DataStore keeps this in the app's private storage, which the Android sandbox
 * protects from other apps. It is not encrypted at rest, so on a rooted device
 * with physical access the tokens are readable — which is one more reason the
 * server treats Play Integrity as the real trust anchor rather than the token
 * alone. Access tokens last 30 minutes, limiting the window either way.
 */
class TokenStore(private val context: Context) {

    private object Keys {
        val ACCESS = stringPreferencesKey("access_token")
        val REFRESH = stringPreferencesKey("refresh_token")
    }

    val isLoggedIn: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.ACCESS] != null }

    suspend fun accessToken(): String? = context.dataStore.data.first()[Keys.ACCESS]

    suspend fun refreshToken(): String? = context.dataStore.data.first()[Keys.REFRESH]

    suspend fun save(access: String, refresh: String?) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ACCESS] = access
            // Refresh rotation is on server-side, but a refresh response may
            // omit it; keep the existing one in that case.
            if (refresh != null) prefs[Keys.REFRESH] = refresh
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
