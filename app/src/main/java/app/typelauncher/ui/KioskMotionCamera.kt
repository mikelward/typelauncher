package app.typelauncher

import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.InitializationException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException

/** Minimum gap between analyzed frames: a few a second is plenty to see someone walk up. */
private const val KIOSK_MOTION_FRAME_INTERVAL_MS = 300L

/**
 * While composed, watches the front camera for motion and calls [onMotion]
 * (on the main thread) whenever successive frames differ.
 *
 * Frames are small (about 320×240), analyzed on a dedicated background thread
 * a few times a second, reduced to a coarse brightness grid, and dropped; no
 * frame is kept, recognized or sent anywhere. Bound to the composition's
 * lifecycle, so the camera stops when the launcher leaves the foreground and
 * when the kiosk display leaves composition. A device with no usable front
 * camera simply never reports motion, and the failure is logged.
 */
@Composable
internal fun KioskMotionCamera(onMotion: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnMotion by rememberUpdatedState(onMotion)
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val analysis = remember {
        val detector = KioskMotionDetector()
        var lastFrameAt = 0L
        var buffer = ByteArray(0)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(320, 240),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { useCase ->
                useCase.setAnalyzer(analysisExecutor) { image ->
                    image.use {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastFrameAt < KIOSK_MOTION_FRAME_INTERVAL_MS) return@use
                        lastFrameAt = now
                        val plane = it.planes[0]
                        val bytes = plane.buffer
                        bytes.rewind()
                        if (buffer.size != bytes.remaining()) buffer = ByteArray(bytes.remaining())
                        bytes.get(buffer)
                        if (detector.onFrame(buffer, it.width, it.height, plane.rowStride)) {
                            mainExecutor.execute { currentOnMotion() }
                        }
                    }
                }
            }
    }
    LaunchedEffect(Unit) {
        provider = try {
            ProcessCameraProvider.awaitInstance(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InitializationException) {
            // The camera service or hardware is unavailable: no motion wake,
            // but the display itself keeps running.
            LauncherDebugLog.failure(e, "KioskMotionCamera: CameraX failed to initialize")
            null
        } catch (e: IllegalStateException) {
            LauncherDebugLog.failure(e, "KioskMotionCamera: camera provider unavailable")
            null
        }
    }
    DisposableEffect(provider, lifecycleOwner) {
        val cameraProvider = provider
        if (cameraProvider != null) {
            try {
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                LauncherDebugLog.event("KioskMotionCamera bound")
            } catch (e: IllegalArgumentException) {
                // No front camera, or it can't serve this use case.
                LauncherDebugLog.failure(e, "KioskMotionCamera: no usable front camera")
            } catch (e: IllegalStateException) {
                LauncherDebugLog.failure(e, "KioskMotionCamera: bind failed")
            }
        }
        onDispose { cameraProvider?.unbind(analysis) }
    }
    DisposableEffect(Unit) {
        onDispose {
            analysis.clearAnalyzer()
            analysisExecutor.shutdown()
        }
    }
}
