package edu.iitgoa.attendance.ui.student

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import edu.iitgoa.attendance.ui.common.ErrorBanner
import edu.iitgoa.attendance.ui.common.PermissionGate
import edu.iitgoa.attendance.util.CameraPreview
import edu.iitgoa.attendance.util.rememberCameraCaptureState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceEnrollScreen(
    state: FaceEnrollState,
    onSubmit: (ByteArray) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(state.done) {
        if (state.done) onDone()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Face setup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        PermissionGate(
            permissions = listOf(Manifest.permission.CAMERA),
            rationaleTitle = "Camera access needed",
            rationale = "Your reference photo is captured here and sent straight to " +
                "the server. It is never saved to your phone.",
            modifier = Modifier.padding(padding),
        ) {
            CapturePane(
                instruction = "Look straight at the camera in good light, with " +
                    "nobody else in frame.",
                buttonLabel = "Capture reference photo",
                isBusy = state.isSubmitting,
                busyLabel = "Saving…",
                error = state.error,
                onCapture = onSubmit,
            )
        }
    }
}

/**
 * Shared camera pane: preview, one action button, and error feedback.
 *
 * Used for both face enrolment and marking attendance so the two feel the same.
 */
@Composable
fun CapturePane(
    instruction: String,
    buttonLabel: String,
    isBusy: Boolean,
    busyLabel: String,
    error: String?,
    onCapture: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cameraState = rememberCameraCaptureState()
    var cameraError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Text(instruction, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            CameraPreview(
                state = cameraState,
                modifier = Modifier.fillMaxSize(),
                onError = { cameraError = it },
            )
        }

        Spacer(Modifier.height(16.dp))

        val message = error ?: cameraError
        if (message != null) {
            ErrorBanner(message)
            Spacer(Modifier.height(16.dp))
        }

        Column(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            if (isBusy) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(busyLabel, style = MaterialTheme.typography.bodyMedium)
            } else {
                Button(
                    enabled = cameraState.isReady,
                    onClick = {
                        cameraError = null
                        scope.launch {
                            runCatching { cameraState.capture(context) }
                                .onSuccess(onCapture)
                                .onFailure {
                                    cameraError = it.message ?: "Could not take the photo."
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(buttonLabel) }
            }
        }
    }
}
