package edu.iitgoa.attendance.data.repo

import edu.iitgoa.attendance.data.ApiResult
import edu.iitgoa.attendance.data.apiCall
import edu.iitgoa.attendance.data.orThrow
import edu.iitgoa.attendance.data.remote.AttendanceApi
import edu.iitgoa.attendance.data.remote.AttendanceRecordDto
import edu.iitgoa.attendance.data.remote.ChallengeDto
import edu.iitgoa.attendance.data.remote.FaceEnrollmentDto
import edu.iitgoa.attendance.data.remote.ManualAttendanceRequest
import edu.iitgoa.attendance.data.remote.SessionDto
import edu.iitgoa.attendance.data.remote.SessionSlotRequest
import edu.iitgoa.attendance.util.LocationFix
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The four fields that identify one session on the server.
 *
 * Times are compared as parsed values server-side, so "10:00" and "10:00:00"
 * refer to the same session.
 */
data class SessionSlot(
    val courseName: String,
    val date: String,
    val startTime: String,
    val endTime: String,
)

fun SessionDto.slot() = SessionSlot(courseName, date, startTime, endTime)

class AttendanceRepository(private val api: AttendanceApi) {

    private val jpeg = "image/jpeg".toMediaType()

    suspend fun challenge(): ApiResult<ChallengeDto> =
        apiCall { api.attendanceChallenge() }

    /**
     * Submits a captured frame and the device's real position.
     *
     * Everything here is evidence, not a verdict. The app does not decide
     * whether the face matched or the student was close enough — the server
     * does, in the same request that writes the row. Moving either judgement
     * into this method would reintroduce exactly the bypass the API was
     * rebuilt to close.
     */
    suspend fun markAttendance(
        slot: SessionSlot,
        jpegBytes: ByteArray,
        fix: LocationFix,
        integrityToken: String?,
        integrityNonce: String?,
    ): ApiResult<String> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("course_name", slot.courseName)
            .addFormDataPart("date", slot.date)
            .addFormDataPart("start_time", slot.startTime)
            .addFormDataPart("end_time", slot.endTime)
            .addFormDataPart("lat", fix.latitude.toString())
            .addFormDataPart("lon", fix.longitude.toString())
            .addFormDataPart("location_accuracy_m", fix.accuracyMetres.toString())
            .addFormDataPart("is_mock_location", fix.isMock.toString())
            .apply {
                // Only sent when the server asked for attestation.
                if (!integrityToken.isNullOrBlank() && !integrityNonce.isNullOrBlank()) {
                    addFormDataPart("integrity_token", integrityToken)
                    addFormDataPart("integrity_nonce", integrityNonce)
                }
            }
            // Raw JPEG bytes, not base64 — a third fewer bytes over mobile data.
            .addFormDataPart("image", "frame.jpg", jpegBytes.toRequestBody(jpeg))
            .build()

        return apiCall { api.markAttendance(body).detail }
    }

    suspend fun enrollFace(jpegBytes: ByteArray): ApiResult<FaceEnrollmentDto> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", "face.jpg", jpegBytes.toRequestBody(jpeg))
            .build()
        return apiCall { api.enrollFace(body) }
    }

    suspend fun studentsInSession(slot: SessionSlot): ApiResult<List<AttendanceRecordDto>> =
        apiCall { api.studentsInSession(slot.toRequest()) }

    suspend fun markManually(
        slot: SessionSlot,
        studentUsername: String,
    ): ApiResult<AttendanceRecordDto> = apiCall {
        api.markAttendanceManually(slot.toManualRequest(studentUsername))
    }

    suspend fun removeAttendance(
        slot: SessionSlot,
        studentUsername: String,
    ): ApiResult<Unit> = apiCall {
        api.removeAttendance(slot.toManualRequest(studentUsername)).orThrow()
    }

    private fun SessionSlot.toRequest() = SessionSlotRequest(
        courseName = courseName,
        date = date,
        startTime = startTime,
        endTime = endTime,
    )

    private fun SessionSlot.toManualRequest(studentUsername: String) = ManualAttendanceRequest(
        courseName = courseName,
        date = date,
        startTime = startTime,
        endTime = endTime,
        studentUsername = studentUsername,
    )
}
