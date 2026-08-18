package edu.iitgoa.attendance.ui.teacher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import edu.iitgoa.attendance.data.ApiResult
import edu.iitgoa.attendance.data.remote.AttendanceRecordDto
import edu.iitgoa.attendance.data.remote.CourseDto
import edu.iitgoa.attendance.data.remote.CourseStatsDto
import edu.iitgoa.attendance.data.remote.SessionDto
import edu.iitgoa.attendance.data.remote.StudentDto
import edu.iitgoa.attendance.data.repo.AttendanceRepository
import edu.iitgoa.attendance.data.repo.CourseRepository
import edu.iitgoa.attendance.data.repo.SessionSlot
import edu.iitgoa.attendance.ui.container
import edu.iitgoa.attendance.util.LocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TeacherHomeState(
    val isLoading: Boolean = true,
    val courses: List<CourseDto> = emptyList(),
    val error: String? = null,
    val message: String? = null,
)

class TeacherHomeViewModel(private val courses: CourseRepository) : ViewModel() {

    private val _state = MutableStateFlow(TeacherHomeState())
    val state: StateFlow<TeacherHomeState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            _state.value = when (val result = courses.createdCourses()) {
                is ApiResult.Ok -> _state.value.copy(isLoading = false, courses = result.value)
                is ApiResult.Err -> _state.value.copy(isLoading = false, error = result.message)
            }
        }
    }

    fun createCourse(name: String) {
        viewModelScope.launch {
            when (val result = courses.createCourse(name)) {
                is ApiResult.Ok -> {
                    _state.value = _state.value.copy(
                        message = "Created ${result.value.name}. " +
                            "Share code ${result.value.verificationCode}.",
                    )
                    refresh()
                }
                is ApiResult.Err -> _state.value = _state.value.copy(error = result.message)
            }
        }
    }

    fun deleteCourse(name: String) {
        viewModelScope.launch {
            when (val result = courses.deleteCourse(name)) {
                is ApiResult.Ok -> {
                    _state.value = _state.value.copy(message = "Deleted $name.")
                    refresh()
                }
                is ApiResult.Err -> _state.value = _state.value.copy(error = result.message)
            }
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, message = null)
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { TeacherHomeViewModel(container.courseRepository) }
        }
    }
}

data class TeacherCourseState(
    val isLoading: Boolean = true,
    val sessions: List<SessionDto> = emptyList(),
    val roster: List<StudentDto> = emptyList(),
    val stats: CourseStatsDto? = null,
    val error: String? = null,
    val message: String? = null,
    val isCreatingSession: Boolean = false,
)

class TeacherCourseViewModel(
    private val courseName: String,
    private val courses: CourseRepository,
    private val location: LocationProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(TeacherCourseState())
    val state: StateFlow<TeacherCourseState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)

            val sessions = courses.sessions(courseName)
            val roster = courses.roster(courseName)
            val stats = courses.stats(courseName)

            val firstError = listOf(sessions, roster, stats)
                .filterIsInstance<ApiResult.Err>()
                .firstOrNull()

            _state.value = _state.value.copy(
                isLoading = false,
                sessions = (sessions as? ApiResult.Ok)?.value ?: emptyList(),
                roster = (roster as? ApiResult.Ok)?.value ?: emptyList(),
                stats = (stats as? ApiResult.Ok)?.value,
                error = firstError?.message,
            )
        }
    }

    /**
     * Starts a session anchored to where the teacher is standing.
     *
     * The geofence is centred on this fix, so it must be taken in the room —
     * which is why the flow asks for location rather than letting anyone type
     * coordinates.
     */
    fun createSession(date: String, startTime: String, endTime: String, radiusM: Double) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isCreatingSession = true, error = null)

            val fix = location.currentFix()
            if (fix == null) {
                _state.value = _state.value.copy(
                    isCreatingSession = false,
                    error = "Could not get your location. The session needs a " +
                        "position to anchor its geofence.",
                )
                return@launch
            }

            when (
                val result = courses.createSession(
                    courseName = courseName,
                    date = date,
                    startTime = startTime,
                    endTime = endTime,
                    lat = fix.latitude,
                    lon = fix.longitude,
                    radiusM = radiusM,
                )
            ) {
                is ApiResult.Ok -> {
                    _state.value = _state.value.copy(
                        isCreatingSession = false,
                        message = "Session created.",
                    )
                    refresh()
                }
                is ApiResult.Err -> _state.value = _state.value.copy(
                    isCreatingSession = false,
                    error = result.message,
                )
            }
        }
    }

    fun deleteSession(session: SessionDto) {
        viewModelScope.launch {
            when (val result = courses.deleteSession(session)) {
                is ApiResult.Ok -> {
                    _state.value = _state.value.copy(message = "Session deleted.")
                    refresh()
                }
                is ApiResult.Err -> _state.value = _state.value.copy(error = result.message)
            }
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, message = null)
    }

    companion object {
        fun factory(courseName: String) = viewModelFactory {
            initializer {
                TeacherCourseViewModel(
                    courseName,
                    container.courseRepository,
                    container.locationProvider,
                )
            }
        }
    }
}

data class SessionAttendanceState(
    val isLoading: Boolean = true,
    val present: List<AttendanceRecordDto> = emptyList(),
    val roster: List<StudentDto> = emptyList(),
    val error: String? = null,
    val message: String? = null,
) {
    /** Enrolled students with no attendance row for this session. */
    val absent: List<StudentDto>
        get() {
            val presentUsernames = present.map { it.username }.toSet()
            return roster.filterNot { it.username in presentUsernames }
        }
}

class SessionAttendanceViewModel(
    private val slot: SessionSlot,
    private val attendance: AttendanceRepository,
    private val courses: CourseRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionAttendanceState())
    val state: StateFlow<SessionAttendanceState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)

            val present = attendance.studentsInSession(slot)
            val roster = courses.roster(slot.courseName)

            _state.value = _state.value.copy(
                isLoading = false,
                present = (present as? ApiResult.Ok)?.value ?: emptyList(),
                roster = (roster as? ApiResult.Ok)?.value ?: emptyList(),
                error = (present as? ApiResult.Err)?.message
                    ?: (roster as? ApiResult.Err)?.message,
            )
        }
    }

    /** The manual override, for when the camera or the WiFi lets a student down. */
    fun markManually(username: String) {
        viewModelScope.launch {
            when (val result = attendance.markManually(slot, username)) {
                is ApiResult.Ok -> {
                    _state.value = _state.value.copy(message = "Marked $username present.")
                    refresh()
                }
                is ApiResult.Err -> _state.value = _state.value.copy(error = result.message)
            }
        }
    }

    fun remove(username: String) {
        viewModelScope.launch {
            when (val result = attendance.removeAttendance(slot, username)) {
                is ApiResult.Ok -> {
                    _state.value = _state.value.copy(message = "Removed $username.")
                    refresh()
                }
                is ApiResult.Err -> _state.value = _state.value.copy(error = result.message)
            }
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, message = null)
    }

    companion object {
        fun factory(slot: SessionSlot) = viewModelFactory {
            initializer {
                SessionAttendanceViewModel(
                    slot,
                    container.attendanceRepository,
                    container.courseRepository,
                )
            }
        }
    }
}
