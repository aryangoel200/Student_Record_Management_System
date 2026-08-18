package edu.iitgoa.attendance.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.iitgoa.attendance.data.CurrentUser
import edu.iitgoa.attendance.data.remote.SessionDto
import edu.iitgoa.attendance.ui.common.EmptyState
import edu.iitgoa.attendance.ui.common.ErrorBanner
import edu.iitgoa.attendance.ui.common.LoadingBox
import edu.iitgoa.attendance.ui.common.SessionCard
import edu.iitgoa.attendance.ui.formatDate
import edu.iitgoa.attendance.ui.theme.statusColors

/**
 * Everything on today, in one list, ordered by when it happens.
 *
 * Replaces having to open each course in turn to find out what is on. Live
 * sessions are pinned to the top and outlined green; the ordering comes from
 * the server so it matches the clock the geofence is checked against.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    user: CurrentUser,
    state: TodayState,
    onOpenSession: (SessionDto) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Today")
                        if (state.date.isNotBlank()) {
                            Text(
                                formatDate(state.date),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading && state.sessions.isEmpty()) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }

        if (state.sessions.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize()) {
                state.error?.let { ErrorBanner(it, Modifier.padding(16.dp)) }
                EmptyState(
                    icon = Icons.Default.EventAvailable,
                    title = "Nothing scheduled today",
                    subtitle = if (user.canManageCourses) {
                        "Start a session from one of your courses when class begins."
                    } else {
                        "You have no classes today. Enjoy it."
                    },
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.error?.let { item { ErrorBanner(it) } }

            if (state.live.isNotEmpty()) {
                item {
                    Text(
                        if (state.openCount == 1) "Happening now"
                        else "Happening now (${state.openCount})",
                        style = MaterialTheme.typography.titleSmall,
                        color = statusColors.live,
                    )
                }
                items(state.live, key = { "live-${it.id}" }) { session ->
                    SessionCard(
                        session = session,
                        showCourseName = true,
                        onClick = { onOpenSession(session) },
                    )
                }
                item { Spacer(Modifier.height(4.dp)) }
            }

            if (state.rest.isNotEmpty()) {
                item {
                    Text(
                        "Rest of today",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                items(state.rest, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        showCourseName = true,
                        onClick = { onOpenSession(session) },
                    )
                }
            }
        }
    }
}
