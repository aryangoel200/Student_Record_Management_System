package edu.iitgoa.attendance.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Auth ------------------------------------------------------------------

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class RegisterRequest(
    val username: String,
    val name: String,
    val email: String,
    val password: String,
    val password2: String,
)

@Serializable
data class TokenDto(val access: String, val refresh: String? = null)

@Serializable
data class UserDto(
    val username: String,
    val name: String,
    val email: String = "",
    val role: String,
    @SerialName("face_enrolled") val faceEnrolled: Boolean = false,
)

@Serializable
data class AuthResponse(val user: UserDto, val token: TokenDto)

@Serializable
data class RefreshRequest(val refresh: String)

@Serializable
data class LogoutRequest(val refresh: String)

@Serializable
data class RoleRequest(val role: String)

/** The uniform error envelope from `common/exceptions.py`. */
@Serializable
data class ApiErrorDto(val detail: String? = null)

// --- Courses ---------------------------------------------------------------

@Serializable
data class CourseDto(
    val id: Int,
    val name: String,
    // Absent from the student-facing serializer: the code is the teacher's to share.
    @SerialName("verification_code") val verificationCode: String? = null,
    val teacher: String,
    @SerialName("teacher_name") val teacherName: String = "",
    @SerialName("enrolled_count") val enrolledCount: Int? = null,
)

@Serializable
data class CreateCourseRequest(val name: String)

@Serializable
data class CourseNameRequest(@SerialName("course_name") val courseName: String)

@Serializable
data class EnrollRequest(
    @SerialName("verification_code_entered") val verificationCode: String,
)

@Serializable
data class EnrollResponse(val detail: String, val course: CourseDto? = null)

// --- Sessions --------------------------------------------------------------

@Serializable
data class SessionDto(
    val id: Int,
    @SerialName("course_name") val courseName: String,
    val date: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    val lat: Double,
    val lon: Double,
    @SerialName("radius_m") val radiusM: Double,
    // Null when the caller is not a student of the course.
    val presence: String? = null,
) {
    val isPresent: Boolean get() = presence == "present"
}

@Serializable
data class SessionSlotRequest(
    @SerialName("course_name") val courseName: String,
    val date: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
)

@Serializable
data class CreateSessionRequest(
    @SerialName("course_name") val courseName: String,
    val date: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    val lat: Double,
    val lon: Double,
    @SerialName("radius_m") val radiusM: Double? = null,
)

// --- Attendance ------------------------------------------------------------

@Serializable
data class StudentDto(val username: String, val name: String, val email: String = "")

@Serializable
data class AttendanceRecordDto(
    val username: String,
    val name: String,
    val email: String = "",
    @SerialName("marked_at") val markedAt: String = "",
    val method: String = "face",
    @SerialName("distance_m") val distanceM: Double? = null,
) {
    val wasManual: Boolean get() = method == "manual"
}

@Serializable
data class MarkAttendanceResponse(
    val detail: String,
    val record: AttendanceRecordDto? = null,
)

@Serializable
data class ManualAttendanceRequest(
    @SerialName("course_name") val courseName: String,
    val date: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    @SerialName("student_username") val studentUsername: String,
)

@Serializable
data class ChallengeDto(
    val nonce: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("integrity_required") val integrityRequired: Boolean,
)

@Serializable
data class BestSessionDto(
    val date: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    val present: Int,
)

@Serializable
data class CourseStatsDto(
    @SerialName("course_name") val courseName: String,
    @SerialName("num_enrolled") val numEnrolled: Int,
    @SerialName("num_sessions") val numSessions: Int,
    @SerialName("avg_rate") val avgRate: Double,
    @SerialName("attendance_rate_pct") val attendanceRatePct: Double,
    @SerialName("best_session") val bestSession: BestSessionDto? = null,
)

@Serializable
data class FaceEnrollmentDto(
    val username: String,
    @SerialName("model_name") val modelName: String,
)
