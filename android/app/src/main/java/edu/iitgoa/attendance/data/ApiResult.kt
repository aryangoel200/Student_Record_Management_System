package edu.iitgoa.attendance.data

import edu.iitgoa.attendance.data.remote.ApiErrorDto
import java.io.IOException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/** Success or a displayable failure. Nothing else needs to catch exceptions. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Err(val message: String, val status: Int? = null) : ApiResult<Nothing>
}

val <T> ApiResult<T>.valueOrNull: T?
    get() = (this as? ApiResult.Ok)?.value

private val errorJson = Json { ignoreUnknownKeys = true }

/**
 * Converts an unsuccessful [retrofit2.Response] into the exception [apiCall] expects.
 *
 * Retrofit only throws [HttpException] when a method returns the body type
 * directly. A method declared as `Response<T>` hands back 4xx and 5xx responses
 * without complaint, so endpoints that return 204 — the deletes — must check
 * this themselves or a 403 would read as success.
 */
fun retrofit2.Response<*>.orThrow() {
    if (!isSuccessful) throw HttpException(this)
}

/**
 * Runs a network call and turns any failure into a message worth showing.
 *
 * The server answers every error with a `detail` string (see
 * `Backend/common/exceptions.py`), so the message is already written for a
 * human — "You are not within the session's location to mark attendance"
 * rather than "HTTP 403".
 */
suspend fun <T> apiCall(block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Ok(block())
} catch (e: HttpException) {
    ApiResult.Err(e.serverDetail() ?: defaultMessageFor(e.code()), e.code())
} catch (e: IOException) {
    ApiResult.Err("Could not reach the server. Check your connection.")
} catch (e: Exception) {
    ApiResult.Err(e.message ?: "Something went wrong.")
}

private fun HttpException.serverDetail(): String? = runCatching {
    val body = response()?.errorBody()?.string()
    if (body.isNullOrBlank()) null
    else errorJson.decodeFromString<ApiErrorDto>(body).detail
}.getOrNull()

private fun defaultMessageFor(status: Int): String = when (status) {
    401 -> "Please sign in again."
    403 -> "You are not allowed to do that."
    404 -> "Not found."
    409 -> "That conflicts with something already recorded."
    422 -> "Could not process that image."
    428 -> "Something needs setting up first."
    429 -> "Too many attempts. Wait a moment and try again."
    503 -> "That feature is unavailable on the server right now."
    else -> "Request failed ($status)."
}
