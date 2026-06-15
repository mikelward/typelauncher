package app.typelauncher

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"
private const val SCREENSHOT_DIR_NAME = "bug-reports"

/**
 * Builds a paste-into-an-issue bug-report payload (build/device info, the
 * launcher's persisted settings, and the in-memory log buffer) and hands it
 * off via [Intent.ACTION_SEND] so the share sheet can deliver it. Also drops
 * the text on the clipboard as a paste fallback.
 */
internal object BugReport {
    /**
     * Captures the screen, builds the text payload, copies the text to the
     * clipboard, and fires the share-sheet chooser. [includeScreenshot] = false
     * (or a capture failure) shares text only.
     */
    suspend fun share(activity: Activity, includeScreenshot: Boolean = true) {
        val text = collectPayload(activity)
        val screenshotUri: Uri? = if (includeScreenshot) captureAndPersistScreenshot(activity) else null
        copyToClipboard(activity, text)
        startShare(activity, text, screenshotUri)
    }

    private fun collectPayload(context: Context): String {
        val dockSettings = DockSettingsStore(context)
        val dockedApps = DockedAppStore(context).dockedAppIds
        val widgetStore = WidgetStore(context)
        return buildBugReportPayload(
            nowMillis = System.currentTimeMillis(),
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            buildType = BuildConfig.BUILD_TYPE,
            applicationId = BuildConfig.APPLICATION_ID,
            isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            deviceManufacturer = Build.MANUFACTURER,
            deviceModel = Build.MODEL,
            androidRelease = Build.VERSION.RELEASE,
            androidSdkInt = Build.VERSION.SDK_INT,
            locale = Locale.getDefault(),
            isDockEnabled = dockSettings.isDockEnabled,
            isAppListIconOnly = dockSettings.isAppListIconOnly,
            isShowAppNameHint = dockSettings.isShowAppNameHint,
            isShowDockedAppsInList = dockSettings.isShowDockedAppsInList,
            dockIconSizeDp = dockSettings.dockIconSizeDp,
            appListSortOrder = dockSettings.appListSortOrder,
            isAgendaEnabled = dockSettings.isAgendaEnabled,
            dockedAppIds = dockedApps,
            widgetPages = widgetStore.widgetPages,
            recentLog = LauncherDebugLog.snapshot(),
        )
    }

    private suspend fun captureAndPersistScreenshot(activity: Activity): Uri? {
        val bitmap = try {
            captureWindow(activity)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            LauncherDebugLog.warning("BugReport.captureWindow failed", t)
            null
        } ?: return null
        // Compressing a full-window PNG and pruning previous files would block the main
        // thread long enough to jank the share-sheet open, so persist on Dispatchers.IO.
        return withContext(Dispatchers.IO) {
            try {
                val dir = File(activity.cacheDir, SCREENSHOT_DIR_NAME).apply { mkdirs() }
                // Keep only the freshest screenshot so the cache doesn't grow unbounded.
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "screenshot-${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                FileProvider.getUriForFile(
                    activity,
                    activity.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX,
                    file,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                LauncherDebugLog.warning("BugReport.persistScreenshot failed", t)
                null
            }
        }
    }

    private suspend fun captureWindow(activity: Activity): Bitmap? {
        val window = activity.window ?: return null
        val view: View = window.decorView
        if (view.width <= 0 || view.height <= 0) return null
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return suspendCancellableCoroutine { cont ->
            val location = IntArray(2)
            view.getLocationInWindow(location)
            val rect = Rect(
                location[0],
                location[1],
                location[0] + view.width,
                location[1] + view.height,
            )
            try {
                requestPixelCopy(window, rect, bitmap) { ok ->
                    cont.resume(if (ok) bitmap else null)
                }
            } catch (t: Throwable) {
                LauncherDebugLog.warning("BugReport.PixelCopy.request threw", t)
                cont.resume(null)
            }
        }
    }

    private fun requestPixelCopy(
        window: Window,
        rect: Rect,
        bitmap: Bitmap,
        onResult: (Boolean) -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())
        PixelCopy.request(window, rect, bitmap, { result ->
            onResult(result == PixelCopy.SUCCESS)
        }, handler)
    }

    private fun startShare(activity: Activity, text: String, screenshotUri: Uri?) {
        val send = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_SUBJECT, "Type Launcher bug report — ${BuildConfig.VERSION_NAME}")
            putExtra(Intent.EXTRA_TEXT, text)
            if (screenshotUri != null) {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, screenshotUri)
                clipData = ClipData.newRawUri("Type Launcher screenshot", screenshotUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
        }
        val chooser = Intent.createChooser(send, activity.getString(R.string.bug_report_chooser_title))
        if (screenshotUri != null) {
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { activity.startActivity(chooser) }
            .onFailure { LauncherDebugLog.warning("BugReport.share intent failed", it) }
    }

    private fun copyToClipboard(context: Context, text: String) {
        runCatching {
            val cm = context.getSystemService(ClipboardManager::class.java) ?: return
            cm.setPrimaryClip(ClipData.newPlainText("Type Launcher bug report", text))
        }.onFailure { LauncherDebugLog.warning("BugReport.clipboard copy failed", it) }
    }
}

/** Walks the [ContextWrapper] chain to find the host [Activity], or returns null. */
internal fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

internal fun buildBugReportPayload(
    nowMillis: Long,
    versionName: String,
    versionCode: Long,
    buildType: String,
    applicationId: String,
    isDebuggable: Boolean,
    deviceManufacturer: String,
    deviceModel: String,
    androidRelease: String,
    androidSdkInt: Int,
    locale: Locale,
    isDockEnabled: Boolean,
    isAppListIconOnly: Boolean,
    isShowAppNameHint: Boolean,
    isShowDockedAppsInList: Boolean,
    dockIconSizeDp: Int,
    appListSortOrder: AppListSortOrder,
    isAgendaEnabled: Boolean,
    dockedAppIds: List<String>,
    widgetPages: List<List<Int>>,
    recentLog: List<String>,
): String {
    val widgetIds = widgetPages.flatten()
    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(nowMillis))
    return buildString {
        appendLine("Type Launcher bug report")
        appendLine("Captured: $timestamp")
        appendLine()
        appendLine("--- Build ---")
        appendLine("Version: $versionName ($versionCode)")
        appendLine("Build type: $buildType")
        appendLine("Application id: $applicationId")
        appendLine("Debuggable: $isDebuggable")
        appendLine()
        appendLine("--- Device ---")
        appendLine("Model: $deviceManufacturer $deviceModel")
        appendLine("Android: $androidRelease (SDK $androidSdkInt)")
        appendLine("Locale: ${locale.toLanguageTag()}")
        appendLine()
        appendLine("--- Settings ---")
        appendLine("Dock enabled: $isDockEnabled")
        appendLine("App list icon-only: $isAppListIconOnly")
        appendLine("Show app name hint: $isShowAppNameHint")
        appendLine("Show docked apps in list: $isShowDockedAppsInList")
        appendLine("Dock icon size: ${dockIconSizeDp}dp")
        appendLine("App list sort order: $appListSortOrder")
        appendLine("Agenda enabled: $isAgendaEnabled")
        appendLine("Docked apps (${dockedAppIds.size}):")
        if (dockedAppIds.isEmpty()) {
            appendLine("  (none)")
        } else {
            dockedAppIds.forEach { appendLine("  - $it") }
        }
        appendLine("Widgets (${widgetIds.size}): ${if (widgetIds.isEmpty()) "(none)" else widgetIds.joinToString()}")
        widgetPages.forEachIndexed { index, pageIds ->
            appendLine("  Page ${index + 1}: ${if (pageIds.isEmpty()) "(empty)" else pageIds.joinToString()}")
        }
        appendLine()
        appendLine("--- Recent log (newest last, ${recentLog.size} of max $LOG_BUFFER_MAX_ENTRIES) ---")
        if (recentLog.isEmpty()) {
            appendLine("(no captured log lines)")
        } else {
            recentLog.forEach { appendLine(it) }
        }
    }
}
