package edu.iitgoa.attendance.ui.student

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import edu.iitgoa.attendance.data.ApiResult
import edu.iitgoa.attendance.data.repo.AttendanceRepository
import edu.iitgoa.attendance.data.repo.SessionSlot
import edu.iitgoa.attendance.ui.container
import edu.iitgoa.attendance.util.IntegrityProvider
import edu.iitgoa.attendance.util.LocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface MarkState {
    data object Idle : MarkState

    /** [step] is shown under the spinner so a slow submit does not look stuck. */
    data class Working(val step: String) : MarkState
    data class Success(val message: String) : MarkState
    data class Failed(val message: String) : MarkState
}

class MarkAttendanceViewModel(
    private val slot: SessionSlot,
    private val attendance: AttendanceRepository,
    private val location: LocationProvider,
    private val integrity: IntegrityProvider,
) : ViewModel() {

    private val _state = MutableStateFlow<MarkState>(MarkState.Idle)
    val state: StateFlow<MarkState> = _state.asStateFlow()

    /**
     * Gathers evidence and submits it.
     *
     * Note what is absent: nowhere does this decide whether the student is
     * close enough or whether the face matches. It collects a position, an
     * attestation and a frame, and lets the server rule on all three.
     */
    fun submit(jpeg: ByteArray) {
        viewModelScope.launch {
            _state.value = MarkState.Working("Finding your location…")
            val fix = location.currentFix()
            if (fix == null) {
                _state.value = MarkState.Failed(
                    "Could not get a location fix. Move somewhere with a clearer " +
                        "view of the sky and try again.",
                )
                return@launch
            }

            _state.value = MarkState.Working("Checking this device…")
            // The challenge is per-attempt: its nonce is single-use, so it can
            // never be cached or reused for a later session.
            var token: String? = null
            var nonce: String? = null
            when (val challenge = attendance.challenge()) {
                is ApiResult.Ok -> {
                    nonce = challenge.value.nonce
                    if (challenge.value.integrityRequired) {
                        token = integrity.tokenFor(challenge.value.nonce)
                        if (token == null) {
                            _state.value = MarkState.Failed(
                                "This device could not be verified with Google Play. " +
                                    "Check that Play Services is up to date.",
                            )
                            return@launch
                        }
                    } else {
                        // Server is not enforcing attestation; send nothing.
                        nonce = null
                    }
                }
                is ApiResult.Err -> {
                    _state.value = MarkState.Failed(challenge.message)
                    return@launch
                }
            }

            _state.value = MarkState.Working("Verifying your face…")
            _state.value = when (
                val result = attendance.markAttendance(slot, jpeg, fix, token, nonce)
            ) {
                is ApiResult.Ok -> MarkState.Success(result.value)
                is ApiResult.Err -> MarkState.Failed(result.message)
            }
        }
    }

    fun reset() {
        _state.value = MarkState.Idle
    }

    companion object {
        fun factory(slot: SessionSlot) = viewModelFactory {
            initializer {
                MarkAttendanceViewModel(
                    slot = slot,
                    attendance = container.attendanceRepository,
                    location = container.locationProvider,
                    integrity = container.integrityProvider,
                )
            }
        }
    }
}
