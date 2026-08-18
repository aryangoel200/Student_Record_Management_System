package edu.iitgoa.attendance.ui.teacher

import android.content.ClipData
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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import edu.iitgoa.attendance.data.CurrentUser
import edu.iitgoa.attendance.data.remote.CourseDto
import edu.iitgoa.attendance.ui.common.EmptyState
import edu.iitgoa.attendance.ui.common.ErrorBanner
import edu.iitgoa.attendance.ui.common.LoadingBox
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeacherHomeScreen(
    user: CurrentUser,
    state: TeacherHomeState,
    onOpenCourse: (String) -> Unit,
    onCreateCourse: (String) -> Unit,
    onDeleteCourse: (String) -> Unit,
    onSetArchived: (String, Boolean) -> Unit,
    onToggleShowArchived: () -> Unit,
    onLogout: () -> Unit,
    onMessagesShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CourseDto?>(null) }
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
                title = { Text("My courses") },
                actions = {
                    IconButton(onClick = onToggleShowArchived) {
                        Icon(
                            if (state.showArchived) Icons.Default.Unarchive
                            else Icons.Default.Archive,
                            contentDescription = if (state.showArchived) {
                                "Hide archived courses"
                            } else {
                                "Show archived courses"
                            },
                        )
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign out")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New course") },
            )
        },
    ) { padding ->
        when {
            state.isLoading && state.courses.isEmpty() -> LoadingBox(Modifier.padding(padding))

            state.courses.isEmpty() -> Column(Modifier.padding(padding)) {
                if (state.error != null) {
                    ErrorBanner(state.error, Modifier.padding(16.dp))
                }
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.LibraryBooks,
                    title = "No courses yet",
                    subtitle = if (user.role.name == "ADMIN") {
                        "As an admin you can see every course once one exists."
                    } else {
                        "Create a course, then share its code with your students."
                    },
                )
            }

            else -> LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.error != null) {
                    item { ErrorBanner(state.error) }
                }
                items(state.courses, key = { it.id }) { course ->
                    TeacherCourseRow(
                        course = course,
                        onClick = { onOpenCourse(course.name) },
                        onDelete = { pendingDelete = course },
                        onSetArchived = { onSetArchived(course.name, it) },
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateCourseDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = {
                showCreateDialog = false
                onCreateCourse(it)
            },
        )
    }

    pendingDelete?.let { course ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${course.name}?") },
            // Deleting cascades server-side, so be explicit about the blast radius.
            text = {
                Text(
                    "This also deletes every session and every attendance record " +
                        "for the course. It cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteCourse(course.name)
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
private fun TeacherCourseRow(
    course: CourseDto,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onSetArchived: (Boolean) -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(course.name, style = MaterialTheme.typography.titleMedium)
                        if (course.isArchived) {
                            Spacer(Modifier.width(8.dp))
                            AssistChip(
                                onClick = {},
                                enabled = false,
                                label = { Text("Archived") },
                            )
                        }
                    }
                    course.enrolledCount?.let {
                        Text(
                            "$it enrolled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Archive is offered before delete on purpose: it hides the
                // course without destroying attendance anyone may later need.
                IconButton(onClick = { onSetArchived(!course.isArchived) }) {
                    Icon(
                        if (course.isArchived) Icons.Default.Unarchive
                        else Icons.Default.Archive,
                        contentDescription = if (course.isArchived) {
                            "Restore course"
                        } else {
                            "Archive course"
                        },
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete course",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            course.verificationCode?.let { code ->
                Spacer(Modifier.height(8.dp))
                AssistChip(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("Course code", code))
                            )
                        }
                    },
                    label = {
                        Text(code, fontFamily = FontFamily.Monospace)
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy join code",
                            modifier = Modifier.width(18.dp),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun CreateCourseDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New course") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Course name") },
                    supportingText = { Text("For example, CS210.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "A join code is generated automatically once the course exists.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
