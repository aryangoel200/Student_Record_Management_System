package edu.iitgoa.attendance.ui.student

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import edu.iitgoa.attendance.data.ApiResult
import edu.iitgoa.attendance.data.remote.CourseDto
import edu.iitgoa.attendance.data.remote.SessionDto
import edu.iitgoa.attendance.data.repo.AttendanceRepository
import edu.iitgoa.attendance.data.repo.AuthRepository
import edu.iitgoa.attendance.data.repo.CourseRepository
import edu.iitgoa.attendance.ui.container
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StudentHomeState(
    val isLoading: Boolean = true,
    val courses: List<CourseDto> = emptyList(),
    val error: String? = null,
    val enrollMessage: String? = null,
    val isEnrolling: Boolean = false,
)

class StudentHomeViewModel(
    private val courses: CourseRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(StudentHomeState())
    val state: StateFlow<StudentHomeState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            _state.value = when (val result = courses.enrolledCourses()) {
                is ApiResult.Ok ->
                    _state.value.copy(isLoading = false, courses = result.value)
                is ApiResult.Err ->
                    _state.value.copy(isLoading = false, error = result.message)
            }
        }
    }

    fun enroll(code: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isEnrolling = true, error = null)
            when (val result = courses.enroll(code)) {
                is ApiResult.Ok -> {
                    _state.value = _state.value.copy(
                        isEnrolling = false,
                        enrollMessage = result.value,
                    )
                    refresh()
                }
                is ApiResult.Err ->
                    _state.value = _state.value.copy(
                        isEnrolling = false,
                        error = result.message,
                    )
            }
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, enrollMessage = null)
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { StudentHomeViewModel(container.courseRepository) }
        }
    }
}

data class CourseSessionsState(
    val isLoading: Boolean = true,
    val active: List<SessionDto> = emptyList(),
    val all: List<SessionDto> = emptyList(),
    val error: String? = null,
) {
    val attended: Int get() = all.count { it.isPresent }
    val attendancePercent: Int
        get() = if (all.isEmpty()) 0 else (100 * attended / all.size)
}

class CourseSessionsViewModel(
    private val courseName: String,
    private val courses: CourseRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CourseSessionsState())
    val state: StateFlow<CourseSessionsState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)

            val allResult = courses.sessions(courseName)
            val activeResult = courses.activeSessions(courseName)

            _state.value = when {
                allResult is ApiResult.Err ->
                    _state.value.copy(isLoading = false, error = allResult.message)
                activeResult is ApiResult.Err ->
                    _state.value.copy(isLoading = false, error = activeResult.message)
                else -> CourseSessionsState(
                    isLoading = false,
                    all = (allResult as ApiResult.Ok).value,
                    active = (activeResult as ApiResult.Ok).value,
                )
            }
        }
    }

    companion object {
        fun factory(courseName: String) = viewModelFactory {
            initializer { CourseSessionsViewModel(courseName, container.courseRepository) }
        }
    }
}

data class FaceEnrollState(
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

class FaceEnrollViewModel(
    private val attendance: AttendanceRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FaceEnrollState())
    val state: StateFlow<FaceEnrollState> = _state.asStateFlow()

    fun submit(jpeg: ByteArray) {
        viewModelScope.launch {
            _state.value = FaceEnrollState(isSubmitting = true)
            _state.value = when (val result = attendance.enrollFace(jpeg)) {
                is ApiResult.Ok -> {
                    // face_enrolled has flipped; reload so the gate opens.
                    auth.refreshProfile()
                    FaceEnrollState(done = true)
                }
                is ApiResult.Err -> FaceEnrollState(error = result.message)
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                FaceEnrollViewModel(container.attendanceRepository, container.authRepository)
            }
        }
    }
}
