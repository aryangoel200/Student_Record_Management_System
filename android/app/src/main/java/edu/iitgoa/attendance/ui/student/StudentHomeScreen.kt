package edu.iitgoa.attendance.ui.student

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import edu.iitgoa.attendance.data.CurrentUser
import edu.iitgoa.attendance.data.remote.CourseDto
import edu.iitgoa.attendance.ui.common.EmptyState
import edu.iitgoa.attendance.ui.common.ErrorBanner
import edu.iitgoa.attendance.ui.common.LoadingBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentHomeScreen(
    user: CurrentUser,
    state: StudentHomeState,
    onOpenCourse: (String) -> Unit,
    onEnroll: (String) -> Unit,
    onEnrollFace: () -> Unit,
    onOpenProfile: () -> Unit,
    onMessagesShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showEnrollDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.enrollMessage) {
        state.enrollMessage?.let {
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
                    IconButton(onClick = onOpenProfile) {
                        Icon(Icons.Default.AccountCircle, contentDescription = "Account")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showEnrollDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Join course") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            // The gate that decides whether attendance is possible at all.
            if (user.needsFaceEnrollment) {
                FaceEnrollmentPrompt(
                    onEnrollFace = onEnrollFace,
                    modifier = Modifier.padding(16.dp),
                )
            }

            if (state.error != null) {
                ErrorBanner(state.error, Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(8.dp))
            }

            when {
                state.isLoading && state.courses.isEmpty() -> LoadingBox()
                state.courses.isEmpty() -> EmptyState(
                    icon = Icons.Default.School,
                    title = "No courses yet",
                    subtitle = "Tap Join course and enter the code your teacher shared.",
                )
                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.courses, key = { it.id }) { course ->
                        CourseRow(course) { onOpenCourse(course.name) }
                    }
                }
            }
        }
    }

    if (showEnrollDialog) {
        EnrollDialog(
            isSubmitting = state.isEnrolling,
            onDismiss = { showEnrollDialog = false },
            onConfirm = { code ->
                showEnrollDialog = false
                onEnroll(code)
            },
        )
    }
}

@Composable
private fun FaceEnrollmentPrompt(onEnrollFace: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Face,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Set up face verification",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "You need a reference photo before you can mark attendance. " +
                    "It is stored as a mathematical signature, not as a picture.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onEnrollFace) { Text("Set up now") }
        }
    }
}

@Composable
private fun CourseRow(course: CourseDto, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(course.name, style = MaterialTheme.typography.titleMedium)
                if (course.teacherName.isNotBlank()) {
                    Text(
                        course.teacherName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun EnrollDialog(
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join a course") },
        text = {
            Column {
                Text("Enter the verification code from your teacher.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase() },
                    label = { Text("Code") },
                    singleLine = true,
                    // The server generates 8 characters from an unambiguous
                    // alphabet; monospace makes them easier to check.
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(code) },
                enabled = code.isNotBlank() && !isSubmitting,
            ) { Text("Join") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
