package edu.iitgoa.attendance.ui.student

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import edu.iitgoa.attendance.ui.common.PermissionGate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkAttendanceScreen(
    courseName: String,
    state: MarkState,
    onSubmit: (ByteArray) -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Mark attendance") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            is MarkState.Success -> SuccessPane(
                courseName = courseName,
                message = state.message,
                onDone = onDone,
                modifier = Modifier.padding(padding),
            )

            else -> PermissionGate(
                // Both are required, and both are checked by the server:
                // the frame proves who, the fix proves where.
                permissions = listOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ),
                rationaleTitle = "Camera and location needed",
                rationale = "Attendance is confirmed by matching your face and " +
                    "checking you are inside the classroom.",
                modifier = Modifier.padding(padding),
            ) {
                CapturePane(
                    instruction = "Look at the camera. Your photo and location are " +
                        "checked on the server before attendance is recorded.",
                    buttonLabel = "Capture and submit",
                    isBusy = state is MarkState.Working,
                    busyLabel = (state as? MarkState.Working)?.step ?: "",
                    error = (state as? MarkState.Failed)?.message,
                    onCapture = { jpeg ->
                        if (state is MarkState.Failed) onRetry()
                        onSubmit(jpeg)
                    },
                )
            }
        }
    }
}

@Composable
private fun SuccessPane(
    courseName: String,
    message: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF2E7D32),
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("You're marked present", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            courseName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("Done")
        }
    }
}
