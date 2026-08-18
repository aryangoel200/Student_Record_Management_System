package edu.iitgoa.attendance.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One position reading, with the two signals the server uses to decide whether
 * to believe it.
 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Float,
    val isMock: Boolean,
)

class LocationProvider(private val context: Context) {

    private val client by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    /**
     * Asks for a fresh, high-accuracy fix.
     *
     * Deliberately not `lastLocation`: a cached fix could be hours old and
     * kilometres away, which is precisely the kind of stale evidence the
     * geofence exists to reject.
     */
    @SuppressLint("MissingPermission")
    suspend fun currentFix(): LocationFix? = suspendCancellableCoroutine { cont ->
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(15_000)
            .setMaxUpdateAgeMillis(0)
            .build()

        client.getCurrentLocation(request, null)
            .addOnSuccessListener { location ->
                cont.resume(location?.toFix())
            }
            .addOnFailureListener { cont.resume(null) }
    }

    private fun Location.toFix() = LocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMetres = accuracy,
        isMock = isMockCompat(),
    )

    /**
     * `isMock` replaced the deprecated `isFromMockProvider` in API 31.
     *
     * We forward whatever the platform says and let the server decide. A
     * patched build could always lie here, which is why Play Integrity is the
     * signal that makes this one meaningful.
     */
    @Suppress("DEPRECATION")
    private fun Location.isMockCompat(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else isFromMockProvider
}
