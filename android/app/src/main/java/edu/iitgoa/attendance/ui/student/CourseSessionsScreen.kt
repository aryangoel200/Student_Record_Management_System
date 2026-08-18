package edu.iitgoa.attendance.ui.student

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import edu.iitgoa.attendance.data.remote.SessionDto
import edu.iitgoa.attendance.ui.common.EmptyState
import edu.iitgoa.attendance.ui.common.ErrorBanner
import edu.iitgoa.attendance.ui.common.LoadingBox
import edu.iitgoa.attendance.ui.common.SessionCard
import edu.iitgoa.attendance.ui.formatDate
import edu.iitgoa.attendance.ui.formatSlot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseSessionsScreen(
    courseName: String,
    state: CourseSessionsState,
    canMarkAttendance: Boolean,
    onMark: (SessionDto) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(courseName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading && state.all.isEmpty() ->
                LoadingBox(Modifier.padding(padding))

            else -> LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.error != null) {
                    item { ErrorBanner(state.error) }
                }

                item { AttendanceSummary(state) }

                if (state.active.isNotEmpty()) {
                    item {
                        SectionHeader("Happening now")
                    }
                    items(state.active, key = { "active-${it.id}" }) { session ->
                        ActiveSessionCard(
                            session = session,
                            canMark = canMarkAttendance,
                            onMark = { onMark(session) },
                        )
                    }
                }

                item { SectionHeader("All sessions") }

                if (state.all.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Default.EventBusy,
                            title = "No sessions yet",
                            subtitle = "Your teacher has not started any sessions " +
                                "for this course.",
                        )
                    }
                } else {
                    items(state.all, key = { it.id }) { SessionRow(it) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Column {
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun AttendanceSummary(state: CourseSessionsState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Your attendance", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "${state.attended} of ${state.all.size} sessions",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (state.all.isEmpty()) 0f else state.attended.toFloat() / state.all.size },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${state.attendancePercent}%",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun ActiveSessionCard(
    session: SessionDto,
    canMark: Boolean,
    onMark: () -> Unit,
) {
    Column {
        SessionCard(session = session, onClick = if (canMark) onMark else null)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onMark, enabled = canMark, modifier = Modifier.fillMaxWidth()) {
            Text(if (canMark) "Mark attendance" else "Set up face verification first")
        }
    }
}

@Composable
private fun SessionRow(session: SessionDto) {
    SessionCard(session = session, showDate = true)
}
