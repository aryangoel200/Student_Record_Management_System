package edu.iitgoa.attendance.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import edu.iitgoa.attendance.data.ApiResult
import edu.iitgoa.attendance.data.remote.SessionDto
import edu.iitgoa.attendance.data.repo.CourseRepository
import edu.iitgoa.attendance.ui.container
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class TodayState(
    val isLoading: Boolean = true,
    val date: String = "",
    val openCount: Int = 0,
    val sessions: List<SessionDto> = emptyList(),
    val error: String? = null,
) {
    /** The server already returns live-first; these just split for headers. */
    val live: List<SessionDto> get() = sessions.filter { it.isOpen }
    val rest: List<SessionDto> get() = sessions.filterNot { it.isOpen }
}

class TodayViewModel(private val courses: CourseRepository) : ViewModel() {

    private val _state = MutableStateFlow(TodayState())
    val state: StateFlow<TodayState> = _state.asStateFlow()

    init {
        refresh()
        // A session becoming live is time-based, so nothing pushes it to us.
        // Re-poll slowly: often enough that a class starting is noticed without
        // the user pulling to refresh, rarely enough to be negligible.
        viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                refresh(showSpinner = false)
            }
        }
    }

    fun refresh(showSpinner: Boolean = true) {
        viewModelScope.launch {
            if (showSpinner) _state.value = _state.value.copy(isLoading = true)
            _state.value = when (val result = courses.today()) {
                is ApiResult.Ok -> TodayState(
                    isLoading = false,
                    date = result.value.date,
                    openCount = result.value.openCount,
                    sessions = result.value.sessions,
                )
                is ApiResult.Err ->
                    _state.value.copy(isLoading = false, error = result.message)
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { TodayViewModel(container.courseRepository) }
        }
    }
}
