package edu.iitgoa.attendance.data

import android.content.Context
import edu.iitgoa.attendance.BuildConfig
import edu.iitgoa.attendance.data.local.TokenStore
import edu.iitgoa.attendance.data.remote.AttendanceApi
import edu.iitgoa.attendance.data.remote.AuthInterceptor
import edu.iitgoa.attendance.data.remote.RefreshApi
import edu.iitgoa.attendance.data.remote.TokenAuthenticator
import edu.iitgoa.attendance.data.repo.AttendanceRepository
import edu.iitgoa.attendance.data.repo.AuthRepository
import edu.iitgoa.attendance.data.repo.CourseRepository
import edu.iitgoa.attendance.util.IntegrityProvider
import edu.iitgoa.attendance.util.LocationProvider
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Hand-rolled dependency graph. Built once, in [edu.iitgoa.attendance.AttendanceApp]. */
class AppContainer(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val converter = json.asConverterFactory("application/json".toMediaType())

    val tokenStore = TokenStore(context.applicationContext)

    val sessionManager = SessionManager()

    private val logging = HttpLoggingInterceptor().apply {
        // Headers only: the body carries JPEG frames and JWTs.
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.HEADERS
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    /** No authenticator here, so a failing refresh cannot recurse. */
    private val refreshClient = OkHttpClient.Builder()
        .addInterceptor(logging)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val refreshApi: RefreshApi = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(refreshClient)
        .addConverterFactory(converter)
        .build()
        .create(RefreshApi::class.java)

    private val client = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(tokenStore))
        .addInterceptor(logging)
        .authenticator(
            TokenAuthenticator(tokenStore, refreshApi) { sessionManager.onSessionExpired() }
        )
        .connectTimeout(20, TimeUnit.SECONDS)
        // Face verification runs a model server-side, so allow it time.
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val api: AttendanceApi = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(client)
        .addConverterFactory(converter)
        .build()
        .create(AttendanceApi::class.java)

    val authRepository = AuthRepository(api, tokenStore, sessionManager)
    val courseRepository = CourseRepository(api)
    val attendanceRepository = AttendanceRepository(api)

    val locationProvider = LocationProvider(context.applicationContext)
    val integrityProvider = IntegrityProvider(context.applicationContext)
}
