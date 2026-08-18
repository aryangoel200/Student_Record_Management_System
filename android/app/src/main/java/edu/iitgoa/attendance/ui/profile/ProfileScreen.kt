package edu.iitgoa.attendance.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import edu.iitgoa.attendance.data.CurrentUser
import edu.iitgoa.attendance.data.Role
import edu.iitgoa.attendance.ui.common.ErrorBanner
import edu.iitgoa.attendance.ui.theme.statusColors

/**
 * The signed-in user's own account.
 *
 * Until now nothing in the app told you who you were signed in as, what role
 * you held, or whether your face was enrolled — all of which change what the
 * app will let you do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    user: CurrentUser,
    state: ProfileState,
    onSave: (name: String, email: String) -> Unit,
    onEnrollFace: () -> Unit,
    onRemoveFace: () -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    onMessagesShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember(user.name) { mutableStateOf(user.name) }
    var email by remember(user.email) { mutableStateOf(user.email) }
    var confirmRemoveFace by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onMessagesShown()
        }
    }

    val dirty = name.trim() != user.name || email.trim() != user.email

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            IdentityHeader(user)
            Spacer(Modifier.height(20.dp))

            state.error?.let {
                ErrorBanner(it)
                Spacer(Modifier.height(12.dp))
            }

            Text("Your details", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Full name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                // Roles are assigned server-side; saying so avoids people
                // hunting for a setting that deliberately does not exist.
                "Your username and role are set by the institute and cannot be " +
                    "changed here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onSave(name, email) },
                enabled = dirty && !state.isSaving && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save changes") }

            Spacer(Modifier.height(24.dp))
            FaceSection(
                user = user,
                state = state,
                onEnrollFace = onEnrollFace,
                onRemoveFace = { confirmRemoveFace = true },
            )

            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = { confirmLogout = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Sign out")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmRemoveFace) {
        AlertDialog(
            onDismissRequest = { confirmRemoveFace = false },
            title = { Text("Remove your face data?") },
            text = {
                Text(
                    "Your stored face signature is deleted. You will not be able " +
                        "to mark attendance until you enrol a new photo.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveFace = false
                    onRemoveFace()
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveFace = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Sign out?") },
            text = { Text("You will need your password to sign back in.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    onLogout()
                }) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun IdentityHeader(user: CurrentUser) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                user.initials(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(user.name, style = MaterialTheme.typography.titleLarge)
            Text(
                user.username,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(user.role.display()) },
            )
        }
    }
}

@Composable
private fun FaceSection(
    user: CurrentUser,
    state: ProfileState,
    onEnrollFace: () -> Unit,
    onRemoveFace: () -> Unit,
) {
    val status = statusColors
    Text("Face verification", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (user.faceEnrolled) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (user.faceEnrolled) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (user.faceEnrolled) status.present else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (user.faceEnrolled) "Face enrolled" else "No face enrolled",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (user.faceEnrolled) {
                    "Only a mathematical signature of your face is stored — never " +
                        "the photograph itself."
                } else if (user.role == Role.STUDENT) {
                    "You cannot mark attendance until you enrol a reference photo."
                } else {
                    "Teachers do not need to enrol a face."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            state.faceModel?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Model: $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onEnrollFace, enabled = !state.isSaving) {
                    Icon(Icons.Default.Face, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (user.faceEnrolled) "Re-enrol" else "Enrol face")
                }
                if (user.faceEnrolled) {
                    OutlinedButton(
                        onClick = onRemoveFace,
                        enabled = !state.isSaving,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("Remove") }
                }
            }
        }
    }
}

private fun CurrentUser.initials(): String =
    name.trim().split(" ").filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifBlank { username.take(2).uppercase() }

private fun Role.display(): String = when (this) {
    Role.STUDENT -> "Student"
    Role.TEACHER -> "Teacher"
    Role.ADMIN -> "Administrator"
}
