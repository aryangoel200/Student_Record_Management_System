package edu.iitgoa.attendance.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Status colours Material 3 has no role for.
 *
 * "Live" and "present" are semantic states rather than brand colours, so they
 * sit outside the dynamic colour scheme deliberately — a green that turns
 * lilac because of someone's wallpaper stops meaning "happening now".
 */
data class StatusColors(
    val live: Color,
    val onLive: Color,
    val liveContainer: Color,
    val onLiveContainer: Color,
    val present: Color,
    val absent: Color,
)

private val LightStatus = StatusColors(
    live = Color(0xFF1B873B),
    onLive = Color.White,
    liveContainer = Color(0xFFCFF5D8),
    onLiveContainer = Color(0xFF06301A),
    present = Color(0xFF2E7D32),
    absent = Color(0xFF8A8F98),
)

private val DarkStatus = StatusColors(
    live = Color(0xFF6FDC8C),
    onLive = Color(0xFF00391C),
    liveContainer = Color(0xFF11532B),
    onLiveContainer = Color(0xFFCFF5D8),
    present = Color(0xFF6FDC8C),
    absent = Color(0xFF9BA1AA),
)

val statusColors: StatusColors
    @Composable
    @ReadOnlyComposable
    get() = if (isSystemInDarkTheme()) DarkStatus else LightStatus
