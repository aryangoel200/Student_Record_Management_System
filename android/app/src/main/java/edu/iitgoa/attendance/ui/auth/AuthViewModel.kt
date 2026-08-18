package edu.iitgoa.attendance.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import edu.iitgoa.attendance.data.ApiResult
import edu.iitgoa.attendance.data.AuthState
import edu.iitgoa.attendance.data.SessionManager
import edu.iitgoa.attendance.data.repo.AuthRepository
import edu.iitgoa.attendance.ui.container
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val isSubmitting: Boolean = false,
    val error: String? = null,
)

class AuthViewModel(
    private val repository: AuthRepository,
    session: SessionManager,
) : ViewModel() {

    val authState: StateFlow<AuthState> = session.state

    private val _ui = MutableStateFlow(AuthUiState())
    val ui: StateFlow<AuthUiState> = _ui.asStateFlow()

    init {
        // Cold start: if a token is stored, fetch the profile behind it.
        viewModelScope.launch { repository.restoreSession() }
    }

    fun login(username: String, password: String) = submit {
        repository.login(username, password)
    }

    fun register(
        username: String,
        name: String,
        email: String,
        password: String,
        confirmPassword: String,
    ) = submit {
        if (password != confirmPassword) {
            ApiResult.Err("Password and confirmation do not match.")
        } else {
            repository.register(username, name, email, password, confirmPassword)
        }
    }

    fun logout() {
        viewModelScope.launch { repository.logout() }
    }

    /** Re-reads the profile, e.g. once face enrolment flips `face_enrolled`. */
    fun refreshProfile() {
        viewModelScope.launch { repository.refreshProfile() }
    }

    fun dismissError() {
        _ui.value = _ui.value.copy(error = null)
    }

    private fun submit(block: suspend () -> ApiResult<*>) {
        viewModelScope.launch {
            _ui.value = AuthUiState(isSubmitting = true)
            _ui.value = when (val result = block()) {
                is ApiResult.Ok -> AuthUiState()
                is ApiResult.Err -> AuthUiState(error = result.message)
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                AuthViewModel(container.authRepository, container.sessionManager)
            }
        }
    }
}
