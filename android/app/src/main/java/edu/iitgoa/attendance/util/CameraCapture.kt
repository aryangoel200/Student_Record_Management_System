package edu.iitgoa.attendance.util

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Holds the CameraX capture use case so a screen can trigger a shot from a
 * button outside the preview.
 */
class CameraCaptureState internal constructor() {

    internal var imageCapture: ImageCapture? = null

    val isReady: Boolean get() = imageCapture != null

    /**
     * Takes a photo and returns it as an upright, downscaled JPEG.
     *
     * The frame never touches disk — it goes straight from the sensor to the
     * request body. Nothing is written to storage or the gallery.
     */
    suspend fun capture(context: Context): ByteArray {
        val capture = imageCapture ?: error("Camera is not ready yet.")

        return suspendCancellableCoroutine { cont ->
            capture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            cont.resume(image.toUprightJpeg())
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resumeWithException(exception)
                    }
                },
            )
        }
    }
}

@Composable
fun rememberCameraCaptureState(): CameraCaptureState = remember { CameraCaptureState() }

/**
 * Live camera preview.
 *
 * Defaults to the front lens — attendance is a selfie check, and the rear
 * camera would make it easy to photograph someone else's face.
 */
@Composable
fun CameraPreview(
    state: CameraCaptureState,
    modifier: Modifier = Modifier,
    lensFacing: Int = CameraSelector.LENS_FACING_FRONT,
    onError: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(lensFacing) {
        runCatching {
            // getInstance returns a ListenableFuture whose get() blocks, so it
            // must not run on the main thread.
            val provider = withContext(Dispatchers.IO) {
                ProcessCameraProvider.getInstance(context).get()
            }
            val preview = Preview.Builder().build().apply {
                surfaceProvider = previewView.surfaceProvider
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                preview,
                capture,
            )
            state.imageCapture = capture
        }.onFailure {
            onError(it.message ?: "Could not start the camera.")
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
