package edu.iitgoa.attendance.util

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Requests a Play Integrity token bound to a server-issued nonce.
 *
 * The nonce comes from `POST Home/attendance/challenge` and is single-use, so
 * a token obtained here cannot be replayed against a later session. Never
 * cache either value.
 */
class IntegrityProvider(private val context: Context) {

    private val manager by lazy { IntegrityManagerFactory.create(context) }

    /**
     * Returns null when a token cannot be obtained — no Play Services, an
     * emulator without Play, or a network failure.
     *
     * Returning null is safe: the server decides what to do about a missing
     * token. If it requires attestation the request is refused; if it does not,
     * nothing is lost. The client never gets to conclude "good enough".
     */
    suspend fun tokenFor(nonce: String): String? = suspendCancellableCoroutine { cont ->
        runCatching {
            manager.requestIntegrityToken(
                IntegrityTokenRequest.builder().setNonce(nonce).build()
            )
                .addOnSuccessListener { response -> cont.resume(response.token()) }
                .addOnFailureListener { error ->
                    Log.w(TAG, "Play Integrity token request failed", error)
                    cont.resume(null)
                }
        }.onFailure {
            Log.w(TAG, "Play Integrity unavailable", it)
            cont.resume(null)
        }
    }

    private companion object {
        const val TAG = "IntegrityProvider"
    }
}
