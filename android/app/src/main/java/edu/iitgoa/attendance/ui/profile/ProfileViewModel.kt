package edu.iitgoa.attendance.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import edu.iitgoa.attendance.data.ApiResult
import edu.iitgoa.attendance.data.repo.AuthRepository
import edu.iitgoa.attendance.ui.container
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProfileState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    /** Model that produced the stored face embedding, if one exists. */
    val faceModel: String? = null,
    val faceEnrolledOn: String? = null,
    val error: String? = null,
    val message: String? = null,
)

class ProfileViewModel(private val auth: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            auth.refreshProfile()
            val enrollment = auth.faceEnrollment()
            _state.value = _state.value.copy(
                isLoading = false,
                faceModel = enrollment?.modelName,
                faceEnrolledOn = enrollment?.createdAt,
            )
        }
    }

    fun save(name: String, email: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isSaving = true, error = null)
            _state.value = when (val result = auth.updateProfile(name, email)) {
                is ApiResult.Ok ->
                    _state.value.copy(isSaving = false, message = "Profile updated.")
                is ApiResult.Err ->
                    _state.value.copy(isSaving = false, error = result.message)
            }
        }
    }

    /**
     * Withdraw the stored face.
     *
     * Deliberately available to the person it belongs to: it is their biometric
     * data, and enrolling a new one is the fix for a bad reference photo.
     */
    fun removeFace() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isSaving = true, error = null)
            when (val result = auth.removeFaceEnrollment()) {
                is ApiResult.Ok -> {
                    _state.value = _state.value.copy(
                        isSaving = false,
                        message = "Face removed. You will need to enrol again to " +
                            "mark attendance.",
                    )
                    refresh()
                }
                is ApiResult.Err ->
                    _state.value = _state.value.copy(
                        isSaving = false,
                        error = result.message,
                    )
            }
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, message = null)
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { ProfileViewModel(container.authRepository) }
        }
    }
}
