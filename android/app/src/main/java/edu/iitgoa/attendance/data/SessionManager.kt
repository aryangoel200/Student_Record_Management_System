package edu.iitgoa.attendance.data

import edu.iitgoa.attendance.data.remote.UserDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Roles, mirroring `Auth.models.Role` on the server. */
enum class Role(val wire: String) {
    STUDENT("student"),
    TEACHER("teacher"),
    ADMIN("admin");

    companion object {
        fun from(value: String?): Role =
            entries.firstOrNull { it.wire == value } ?: STUDENT
    }
}

data class CurrentUser(
    val username: String,
    val name: String,
    val email: String,
    val role: Role,
    val faceEnrolled: Boolean,
) {
    /** Teachers and admins share every course-management screen. */
    val canManageCourses: Boolean get() = role == Role.TEACHER || role == Role.ADMIN

    val isStudent: Boolean get() = role == Role.STUDENT

    /** A student cannot mark attendance until a reference face exists. */
    val needsFaceEnrollment: Boolean get() = isStudent && !faceEnrolled
}

fun UserDto.toCurrentUser() = CurrentUser(
    username = username,
    name = name,
    email = email,
    role = Role.from(role),
    faceEnrolled = faceEnrolled,
)

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val user: CurrentUser) : AuthState
}

/** Single source of truth for who is signed in. */
class SessionManager {

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _expiredEvents = MutableStateFlow(0)
    val expiredEvents: StateFlow<Int> = _expiredEvents.asStateFlow()

    fun onSignedIn(user: CurrentUser) {
        _state.value = AuthState.SignedIn(user)
    }

    fun onSignedOut() {
        _state.value = AuthState.SignedOut
    }

    /** Called from the OkHttp authenticator when a refresh finally fails. */
    fun onSessionExpired() {
        _state.value = AuthState.SignedOut
        _expiredEvents.value += 1
    }
}
