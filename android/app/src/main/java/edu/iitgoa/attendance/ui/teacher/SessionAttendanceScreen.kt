package edu.iitgoa.attendance.ui.teacher

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.iitgoa.attendance.data.remote.AttendanceRecordDto
import edu.iitgoa.attendance.data.remote.StudentDto
import edu.iitgoa.attendance.ui.common.ErrorBanner
import edu.iitgoa.attendance.ui.common.LoadingBox
import edu.iitgoa.attendance.ui.formatDate
import edu.iitgoa.attendance.ui.formatSlot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionAttendanceScreen(
    courseName: String,
    date: String,
    startTime: String,
    endTime: String,
    state: SessionAttendanceState,
    onMarkManually: (String) -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
    onMessagesShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                title = {
                    Column {
                        Text(courseName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${formatDate(date)} · ${formatSlot(startTime, endTime)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading && state.present.isEmpty() && state.roster.isEmpty()) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.error != null) {
                item { ErrorBanner(state.error) }
            }

            item {
                Text(
                    "${state.present.size} of ${state.roster.size} present",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (state.present.isNotEmpty()) {
                item { SectionLabel("Present") }
                items(state.present, key = { "p-${it.username}" }) { record ->
                    PresentRow(record, onRemove = { onRemove(record.username) })
                }
            }

            if (state.absent.isNotEmpty()) {
                item { SectionLabel("Not marked") }
                items(state.absent, key = { "a-${it.username}" }) { student ->
                    AbsentRow(student, onMark = { onMarkManually(student.username) })
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Column {
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun PresentRow(record: AttendanceRecordDto, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(record.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    record.username,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                // A manual override is visibly different from a verified mark,
                // which is the point of recording `method` server-side.
                if (record.wasManual) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("Marked by teacher") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.EditNote,
                                contentDescription = null,
                                Modifier.width(18.dp),
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                } else {
                    val distance = record.distanceM?.let { " · ${it.toInt()} m away" } ?: ""
                    Text(
                        "Face verified$distance",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove attendance",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AbsentRow(student: StudentDto, onMark: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(student.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    student.username,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onMark) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Mark present")
            }
        }
    }
}
