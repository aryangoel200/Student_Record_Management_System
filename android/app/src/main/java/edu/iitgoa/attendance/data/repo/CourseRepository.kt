package edu.iitgoa.attendance.data.repo

import edu.iitgoa.attendance.data.ApiResult
import edu.iitgoa.attendance.data.apiCall
import edu.iitgoa.attendance.data.orThrow
import edu.iitgoa.attendance.data.remote.AttendanceApi
import edu.iitgoa.attendance.data.remote.CourseDto
import edu.iitgoa.attendance.data.remote.CourseNameRequest
import edu.iitgoa.attendance.data.remote.CourseStatsDto
import edu.iitgoa.attendance.data.remote.CreateCourseRequest
import edu.iitgoa.attendance.data.remote.CreateSessionRequest
import edu.iitgoa.attendance.data.remote.EnrollRequest
import edu.iitgoa.attendance.data.remote.SessionDto
import edu.iitgoa.attendance.data.remote.SessionSlotRequest
import edu.iitgoa.attendance.data.remote.StudentDto

class CourseRepository(private val api: AttendanceApi) {

    suspend fun enrolledCourses(): ApiResult<List<CourseDto>> =
        apiCall { api.enrolledCourses() }

    suspend fun createdCourses(): ApiResult<List<CourseDto>> =
        apiCall { api.createdCourses() }

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
    ): ApiResult<SessionDto> = apiCall {
        api.createSession(
            CreateSessionRequest(
                courseName = courseName,
                date = date,
                startTime = startTime,
                endTime = endTime,
                lat = lat,
                lon = lon,
                radiusM = radiusM,
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
