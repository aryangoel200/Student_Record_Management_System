package edu.iitgoa.attendance.data.repo

import edu.iitgoa.attendance.data.ApiResult
import edu.iitgoa.attendance.data.apiCall
import edu.iitgoa.attendance.data.orThrow
import edu.iitgoa.attendance.data.remote.AttendanceApi
import edu.iitgoa.attendance.data.remote.CourseDto
import edu.iitgoa.attendance.data.remote.CourseNameRequest
import edu.iitgoa.attendance.data.remote.ArchiveCourseRequest
import edu.iitgoa.attendance.data.remote.CourseStatsDto
import edu.iitgoa.attendance.data.remote.CourseStudentStatsDto
import edu.iitgoa.attendance.data.remote.CreatedSessionsDto
import edu.iitgoa.attendance.data.remote.CreateCourseRequest
import edu.iitgoa.attendance.data.remote.CreateSessionRequest
import edu.iitgoa.attendance.data.remote.EnrollRequest
import edu.iitgoa.attendance.data.remote.SessionDto
import edu.iitgoa.attendance.data.remote.SeriesRequest
import edu.iitgoa.attendance.data.remote.SessionSlotRequest
import edu.iitgoa.attendance.data.remote.StudentDto
import edu.iitgoa.attendance.data.remote.TodayFeedDto

class CourseRepository(private val api: AttendanceApi) {

    suspend fun enrolledCourses(): ApiResult<List<CourseDto>> =
        apiCall { api.enrolledCourses() }

    suspend fun createdCourses(includeArchived: Boolean = false): ApiResult<List<CourseDto>> =
        apiCall {
            api.createdCourses(
                if (includeArchived) mapOf("include_archived" to "true") else emptyMap()
            )
        }

    suspend fun createCourse(name: String): ApiResult<CourseDto> =
        apiCall { api.createCourse(CreateCourseRequest(name.trim())) }

    suspend fun enroll(code: String): ApiResult<String> =
        apiCall { api.enrollInCourse(EnrollRequest(code.trim().uppercase())).detail }

    suspend fun deleteCourse(courseName: String): ApiResult<Unit> =
        apiCall { api.deleteCourse(CourseNameRequest(courseName)).orThrow() }

    suspend fun roster(courseName: String): ApiResult<List<StudentDto>> =
        apiCall { api.roster(CourseNameRequest(courseName)) }

    suspend fun stats(courseName: String): ApiResult<CourseStatsDto> =
        apiCall { api.courseStats(CourseNameRequest(courseName)) }

    suspend fun studentStats(courseName: String): ApiResult<CourseStudentStatsDto> =
        apiCall { api.courseStudentStats(CourseNameRequest(courseName)) }

    /** Archive or restore. Nothing is deleted either way. */
    suspend fun setArchived(courseName: String, archived: Boolean): ApiResult<Unit> =
        apiCall { api.archiveCourse(ArchiveCourseRequest(courseName, archived)).orThrow() }

    /** Everything on today, across every course, already in display order. */
    suspend fun today(): ApiResult<TodayFeedDto> = apiCall { api.today() }

    suspend fun deleteSeries(seriesId: String): ApiResult<Unit> =
        apiCall { api.deleteSessionSeries(SeriesRequest(seriesId)).orThrow() }

    suspend fun sessions(courseName: String): ApiResult<List<SessionDto>> =
        apiCall { api.sessions(CourseNameRequest(courseName)) }

    suspend fun activeSessions(courseName: String): ApiResult<List<SessionDto>> =
        apiCall { api.activeSessions(CourseNameRequest(courseName)) }

    suspend fun createSession(
        courseName: String,
        date: String,
        startTime: String,
        endTime: String,
        lat: Double,
        lon: Double,
        radiusM: Double?,
        repeat: String = "none",
        repeatInterval: Int = 1,
        repeatCount: Int? = null,
    ): ApiResult<CreatedSessionsDto> = apiCall {
        api.createSession(
            CreateSessionRequest(
                courseName = courseName,
                date = date,
                startTime = startTime,
                endTime = endTime,
                lat = lat,
                lon = lon,
                radiusM = radiusM,
                repeat = repeat,
                repeatInterval = repeatInterval,
                repeatCount = repeatCount,
            )
        )
    }

    suspend fun deleteSession(session: SessionDto): ApiResult<Unit> = apiCall {
        api.deleteSession(
            SessionSlotRequest(
                courseName = session.courseName,
                date = session.date,
                startTime = session.startTime,
                endTime = session.endTime,
            )
        ).orThrow()
    }
}
