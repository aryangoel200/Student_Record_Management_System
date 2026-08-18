package edu.iitgoa.attendance.ui.teacher

import android.Manifest
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.iitgoa.attendance.data.remote.SessionDto
import edu.iitgoa.attendance.ui.common.EmptyState
import edu.iitgoa.attendance.ui.common.ErrorBanner
import edu.iitgoa.attendance.ui.common.LoadingBox
import edu.iitgoa.attendance.ui.common.PermissionGate
import edu.iitgoa.attendance.ui.formatDate
import edu.iitgoa.attendance.ui.formatSlot
import edu.iitgoa.attendance.ui.wireTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val TABS = listOf("Sessions", "Students", "Stats")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeacherCourseScreen(
    courseName: String,
    state: TeacherCourseState,
    onOpenSession: (SessionDto) -> Unit,
    onCreateSession: (date: String, start: String, end: String, radius: Double) -> Unit,
    onDeleteSession: (SessionDto) -> Unit,
    onBack: () -> Unit,
    onMessagesShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableIntStateOf(0) }
    var showCreate by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SessionDto?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onMessagesShown()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
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
        floatingActionButton = {
            if (tab == 0) {
                ExtendedFloatingActionButton(
                    onClick = { showCreate = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Start session") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                TABS.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title) },
                    )
                }
            }

            if (state.error != null) {
                ErrorBanner(state.error, Modifier.padding(16.dp))
            }

            if (state.isLoading && state.sessions.isEmpty()) {
                LoadingBox()
                return@Column
            }

            when (tab) {
                0 -> SessionsTab(
                    sessions = state.sessions,
                    onOpen = onOpenSession,
                    onDelete = { pendingDelete = it },
                )
                1 -> StudentsTab(state)
                else -> StatsTab(state)
            }
        }
    }

    if (showCreate) {
        CreateSessionSheet(
            isSubmitting = state.isCreatingSession,
            onDismiss = { showCreate = false },
            onConfirm = { date, start, end, radius ->
                showCreate = false
                onCreateSession(date, start, end, radius)
            },
        )
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this session?") },
            text = {
                Text(
                    "${formatDate(session.date)}, " +
                        "${formatSlot(session.startTime, session.endTime)}. " +
                        "Every attendance record for it is deleted too.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSession(session)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SessionsTab(
    sessions: List<SessionDto>,
    onOpen: (SessionDto) -> Unit,
    onDelete: (SessionDto) -> Unit,
) {
    if (sessions.isEmpty()) {
        EmptyState(
            icon = Icons.Default.EventBusy,
            title = "No sessions yet",
            subtitle = "Start one while you are standing in the classroom — the " +
                "geofence is anchored to your position.",
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sessions, key = { it.id }) { session ->
            Card(onClick = { onOpen(session) }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            formatDate(session.date),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            formatSlot(session.startTime, session.endTime),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${session.radiusM.toInt()} m radius",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onDelete(session) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete session",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudentsTab(state: TeacherCourseState) {
    if (state.roster.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Groups,
            title = "Nobody enrolled yet",
            subtitle = "Share the course join code so students can enrol.",
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.roster, key = { it.username }) { student ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(student.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        student.username,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsTab(state: TeacherCourseState) {
    val stats = state.stats ?: return
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatRow("Enrolled students", stats.numEnrolled.toString())
        StatRow("Sessions held", stats.numSessions.toString())
        StatRow("Average present per session", stats.avgRate.toString())
        StatRow("Attendance rate", "${stats.attendanceRatePct}%")
        stats.bestSession?.let {
            StatRow(
                "Best attended",
                "${formatDate(it.date)} (${it.present})",
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateSessionSheet(
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (date: String, start: String, end: String, radius: Double) -> Unit,
) {
    var stage by remember { mutableIntStateOf(0) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var startTime by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("100") }

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = System.currentTimeMillis(),
    )
    val startState = rememberTimePickerState(is24Hour = true)
    val endState = rememberTimePickerState(is24Hour = true)

    when (stage) {
        0 -> DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        date = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                    }
                    stage = 1
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) { DatePicker(state = datePickerState) }

        1 -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Start time") },
            text = { TimePicker(state = startState) },
            confirmButton = {
                TextButton(onClick = {
                    startTime = wireTime(startState.hour, startState.minute)
                    stage = 2
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )

        2 -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("End time") },
            text = { TimePicker(state = endState) },
            confirmButton = {
                TextButton(onClick = { stage = 3 }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )

        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Geofence") },
            text = {
                Column {
                    // The permission is requested here rather than up front,
                    // because this is the moment the position is actually taken.
                    PermissionGate(
                        permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        rationaleTitle = "Location needed",
                        rationale = "The session's geofence is centred on where " +
                            "you are standing now.",
                    ) {
                        Column {
                            Text(
                                "The geofence centres on your current position, so " +
                                    "start the session from inside the classroom.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = radius,
                                onValueChange = { radius = it.filter(Char::isDigit) },
                                label = { Text("Radius (metres)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isSubmitting && radius.isNotBlank(),
                    onClick = {
                        onConfirm(
                            date.toString(),
                            startTime,
                            wireTime(endState.hour, endState.minute),
                            radius.toDoubleOrNull() ?: 100.0,
                        )
                    },
                ) { Text("Start session") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}
