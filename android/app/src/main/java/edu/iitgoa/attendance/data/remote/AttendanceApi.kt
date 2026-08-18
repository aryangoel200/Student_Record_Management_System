package edu.iitgoa.attendance.data.remote

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * The server API.
 *
 * Paths match `Backend/Home/urls.py` and `Backend/Auth/urls.py`. Most endpoints
 * are POST even when they only read, which mirrors the existing server routes.
 */
interface AttendanceApi {

    // --- Auth ---

    @POST("Auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("Auth/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    @POST("Auth/logout")
    suspend fun logout(@Body body: LogoutRequest): Response<Unit>

    @GET("Auth/profile")
    suspend fun profile(): UserDto

    // --- Courses ---

    @POST("Home/show_enrolled")
    suspend fun enrolledCourses(@Body body: Map<String, String> = emptyMap()): List<CourseDto>

    @POST("Home/show_created")
    suspend fun createdCourses(@Body body: Map<String, String> = emptyMap()): List<CourseDto>

    @POST("Home/course")
    suspend fun createCourse(@Body body: CreateCourseRequest): CourseDto

    @POST("Home/course_registration")
    suspend fun enrollInCourse(@Body body: EnrollRequest): EnrollResponse

    @POST("Home/delete_course")
    suspend fun deleteCourse(@Body body: CourseNameRequest): Response<Unit>

    @POST("Home/show_students")
    suspend fun roster(@Body body: CourseNameRequest): List<StudentDto>

    @POST("Home/course_stats")
    suspend fun courseStats(@Body body: CourseNameRequest): CourseStatsDto

    // --- Sessions ---

    @POST("Home/create")
    suspend fun createSession(@Body body: CreateSessionRequest): SessionDto

    @POST("Home/show_sessions")
    suspend fun sessions(@Body body: CourseNameRequest): List<SessionDto>

    @POST("Home/show_active_sessions")
    suspend fun activeSessions(@Body body: CourseNameRequest): List<SessionDto>

    @POST("Home/delete_session")
    suspend fun deleteSession(@Body body: SessionSlotRequest): Response<Unit>

    @POST("Home/show_students_in_session")
    suspend fun studentsInSession(@Body body: SessionSlotRequest): List<AttendanceRecordDto>

    // --- Attendance ---

    @POST("Home/attendance/challenge")
    suspend fun attendanceChallenge(
        @Body body: Map<String, String> = emptyMap(),
    ): ChallengeDto

    /**
     * Marks the caller present.
     *
     * Takes a pre-built [okhttp3.MultipartBody] so the JPEG goes up as raw
     * bytes. Base64 would cost a third more on a mobile connection, and the
     * server accepts a file part precisely to avoid that.
     */
    @POST("Home/attendance")
    suspend fun markAttendance(@Body body: RequestBody): MarkAttendanceResponse

    @POST("Home/attendance/manual")
    suspend fun markAttendanceManually(
        @Body body: ManualAttendanceRequest,
    ): AttendanceRecordDto

    @POST("Home/attendance/remove")
    suspend fun removeAttendance(@Body body: ManualAttendanceRequest): Response<Unit>

    // --- Face ---

    @POST("Face_Recog/register_image")
    suspend fun enrollFace(@Body body: RequestBody): FaceEnrollmentDto

    @GET("Face_Recog/enrollment")
    suspend fun faceEnrollment(): Response<ResponseBody>
}
