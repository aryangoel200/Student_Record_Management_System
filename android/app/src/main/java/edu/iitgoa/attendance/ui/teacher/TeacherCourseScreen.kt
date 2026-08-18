package edu.iitgoa.attendance.ui.teacher

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
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
import edu.iitgoa.attendance.data.remote.StudentAttendanceDto
import edu.iitgoa.attendance.ui.common.EmptyState
import edu.iitgoa.attendance.ui.common.ErrorBanner
import edu.iitgoa.attendance.ui.common.LoadingBox
import edu.iitgoa.attendance.ui.common.SessionCard
import edu.iitgoa.attendance.ui.theme.statusColors
import edu.iitgoa.attendance.ui.common.PermissionGate
import edu.iitgoa.attendance.ui.formatDate
import edu.iitgoa.attendance.ui.formatSlot
import edu.iitgoa.attendance.ui.wireTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val TABS = listOf("Sessions", "Students", "Overview")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeacherCourseScreen(
    courseName: String,
    state: TeacherCourseState,
    onOpenSession: (SessionDto) -> Unit,
    onCreateSession: (
        date: String, start: String, end: String, radius: Double,
        repeat: String, interval: Int, count: Int?,
    ) -> Unit,
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
                1 -> StudentDashboardTab(state)
                else -> StatsTab(state)
            }
        }
    }

    if (showCreate) {
        CreateSessionSheet(
            isSubmitting = state.isCreatingSession,
            onDismiss = { showCreate = false },
            onConfirm = { date, start, end, radius, repeat, interval, count ->
                showCreate = false
                onCreateSession(date, start, end, radius, repeat, interval, count)
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
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Live first, so the class in progress is never buried under history.
        val ordered = sessions.sortedWith(
            compareByDescending<SessionDto> { it.isOpen }
                .thenByDescending { it.date }
                .thenByDescending { it.startTime }
        )
        items(ordered, key = { it.id }) { session ->
            SessionCard(
                session = session,
                showDate = true,
                onClick = { onOpen(session) },
                trailing = {
                    IconButton(onClick = { onDelete(session) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete session",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun StudentDashboardTab(state: TeacherCourseState) {
    if (state.studentStats.isEmpty()) {
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
        item {
            Text(
                "Lowest attendance first",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(state.studentStats, key = { it.username }) { row ->
            StudentStatRow(row)
        }
    }
}

@Composable
private fun StudentStatRow(row: StudentAttendanceDto) {
    // Traffic-light banding so a teacher can scan the list rather than read it.
    val tint = when {
        row.attendancePct >= 75 -> statusColors.present
        row.attendancePct >= 50 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        row.username,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${row.attendancePct}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = tint,
                    )
                    Text(
                        "${row.attended}/${row.totalSessions}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (row.attendancePct / 100.0).toFloat() },
                color = tint,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A student with no enrolled face cannot mark attendance at
                // all, which explains a low percentage that is not truancy.
                if (!row.faceEnrolled) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("No face enrolled") },
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (row.manualCount > 0) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("${row.manualCount} manual") },
                    )
                    Spacer(Modifier.width(8.dp))
                }
                row.lastSeen?.let {
                    Text(
                        "last seen ${formatDate(it)}",
                        style = MaterialTheme.typography.labelSmall,
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
    onConfirm: (
        date: String, start: String, end: String, radius: Double,
        repeat: String, interval: Int, count: Int?,
    ) -> Unit,
) {
    var stage by remember { mutableIntStateOf(0) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var startTime by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("100") }
    var repeat by remember { mutableStateOf(RepeatOption.NONE) }
    var interval by remember { mutableStateOf("1") }
    var occurrences by remember { mutableStateOf("12") }

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
            confirmButton = { TextButton(onClick = { stage = 3 }) { Text("Next") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )

        3 -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Repeat") },
            text = {
                Column {
                    RepeatOption.entries.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = repeat == option,
                                    onClick = { repeat = option },
                                )
                                .padding(vertical = 6.dp),
                        ) {
                            RadioButton(
                                selected = repeat == option,
                                onClick = { repeat = option },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(option.label)
                        }
                    }

                    if (repeat != RepeatOption.NONE) {
                        Spacer(Modifier.height(12.dp))
                        Row {
                            OutlinedTextField(
                                value = interval,
                                onValueChange = { interval = it.filter(Char::isDigit) },
                                label = { Text("Every") },
                                suffix = { Text(repeat.unit) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = occurrences,
                                onValueChange = { occurrences = it.filter(Char::isDigit) },
                                label = { Text("Times") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            repeat.explain(interval, occurrences, date),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { stage = 4 }) { Text("Next") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )

        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Geofence") },
            text = {
                Column {
                    // Asked for here rather than up front, because this is the
                    // moment the position is actually taken.
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
                            repeat.wire,
                            interval.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                            if (repeat == RepeatOption.NONE) null
                            else occurrences.toIntOrNull()?.coerceIn(1, 60) ?: 1,
                        )
                    },
                ) { Text(if (repeat == RepeatOption.NONE) "Start session" else "Schedule") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}

/** Mirrors `Home.models.Repeat` on the server. */
private enum class RepeatOption(
    val wire: String,
    val label: String,
    val unit: String,
) {
    NONE("none", "Does not repeat", ""),
    DAILY("daily", "Daily", "days"),
    WEEKLY("weekly", "Weekly", "weeks"),
    MONTHLY("monthly", "Monthly", "months");

    /** Plain-English preview, so nobody has to guess what they just set up. */
    fun explain(interval: String, count: String, from: LocalDate): String {
        val n = interval.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val times = count.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val every = if (n == 1) "every $unit".dropLast(1) else "every $n $unit"
        val base = "Creates $times sessions, $every, starting ${from}."
        return if (this == MONTHLY) {
            "$base Months without a ${from.dayOfMonth}th are skipped."
        } else {
            base
        }
    }
}
