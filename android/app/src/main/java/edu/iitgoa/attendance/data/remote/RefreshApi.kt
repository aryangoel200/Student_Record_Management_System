package edu.iitgoa.attendance.data.remote

import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Token refresh, on its own client.
 *
 * Deliberately separate from [AttendanceApi]: the authenticator that performs
 * the refresh is installed on the main client, so calling refresh through that
 * client would be circular — a failed refresh would try to refresh itself.
 */
interface RefreshApi {

    @POST("Auth/token/refresh")
    suspend fun refresh(@Body body: RefreshRequest): TokenDto
}
