package app.typelauncher

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Build
import android.os.Process
import android.widget.Toast
import androidx.core.content.FileProvider
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.android.DebugFileSink
import com.mikelward.androidlog.android.DebugReport
import com.mikelward.androidlog.android.CollectedReport
import com.mikelward.androidlog.android.ProcessExits
import com.mikelward.androidlog.android.ShareOutcome
import com.mikelward.androidlog.android.ReportScreenshot
import com.mikelward.androidlog.boundedLogTail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"
private const val SCREENSHOT_DIR_NAME = "bug-reports"

/**
 * Builds a paste-into-an-issue bug-report payload (build/device info, the
 * launcher's persisted settings, and the in-memory log buffer) and hands it to
 * androidlog's [DebugReport], the path every app in the fleet shares through:
 * it appends the earlier runs, holds the report to a size that pastes whole,
 * copies it to the clipboard, opens the share sheet, and lowers the crash
 * banner once the copy lands. What is here is this app's section of the report.
 */
internal object BugReport {
    /**
     * Captures the screen, builds the text payload, and delivers both.
     * [includeScreenshot] = false (or a capture failure) shares text only.
     *
     * Sharing deletes nothing: `ACTION_SEND` gives no delivery or selection
     * callback, so whether the report was sent is unknowable, and the earlier
     * runs stay under the log's own retention. A copy that lands on the
     * clipboard — which the user can still get at — lowers the post-crash
     * banner; a share that reached nobody leaves it up for a retry.
     *
     * [mainDispatcher], [payloadCollect], [screenshotCapture] and
     * [deliverReport] are injectable test seams (production uses the
     * defaults): each can fail on its own, and the failure notice below is
     * behavior a test must be able to drive every way without a real window,
     * `ClipboardManager`, or share target (`BugReportShareTest`).
     */
    suspend fun share(
        activity: Activity,
        includeScreenshot: Boolean = true,
        mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
        payloadCollect: (Context, DebugFileSink?) -> CollectedReport = ::collectReport,
        screenshotCapture: suspend (Activity) -> Uri? = ::captureAndPersistScreenshot,
        deliverReport: (Activity, CollectedReport, Uri?) -> ShareOutcome = ::deliverViaLibrary,
    ) {
        val fileSink = (activity.applicationContext as? TypeLauncherApp)?.debugFileSink
        // Build the payload off the main thread: it reads persisted settings and
        // up to a few prior-run log files, and share() normally runs in a
        // main-thread UI scope (Codex on PR #592).
        val report = try {
            withContext(Dispatchers.IO) { payloadCollect(activity, fileSink) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // A report is most useful after something has already gone wrong.
            // Never turn a failure while inspecting that state into another app
            // crash — this runs from a UI tap, where an escaping throwable takes
            // the launcher down. Retain a small shareable diagnostic instead.
            //
            // No sink: the fallback reads no prior run, so it cannot repeat a
            // failure that came from reading one, and — carrying none — it
            // leaves the crash banner up for the next share.
            LauncherDebugLog.failure(t, "BugReport payload collection failed")
            DebugReport.collect(LauncherDebugLog, sink = null) { buildFallbackPayload(t) }
        }
        // The clipboard and chooser hand-off touch the Activity, so pin them to
        // the main thread: the payload build above hops to IO, and its
        // continuation must not leave this on a worker thread. The screenshot
        // capture runs off the main thread itself (inside ReportScreenshot,
        // which hops to the main thread only to read the window geometry), so
        // calling it from here suspends this block to IO and resumes on the main
        // thread for the hand-off.
        withContext(mainDispatcher) {
            val screenshotUri: Uri? = if (includeScreenshot) screenshotCapture(activity) else null
            // Guarded: deliverReport is an injectable seam, and share() runs in
            // a caller's coroutine scope where an escaping throwable would take
            // the app down with it — the one thing a bug-report path must never
            // do.
            val outcome = runCatching { deliverReport(activity, report, screenshotUri) }
                .onFailure { LauncherDebugLog.failure(it, "BugReport delivery threw") }
                .getOrDefault(ShareOutcome.FAILED)
            // Neither route landed: the tap would otherwise do nothing visible at
            // all — no chooser, nothing on the clipboard — and the user would
            // retry into the same silence. Say so instead.
            if (outcome == ShareOutcome.FAILED) notifyShareFailed(activity)
        }
    }

    /**
     * The report as the library builds it: this app's section, then the earlier
     * runs, which the library reads and appends itself so that carrying them and
     * lowering the crash banner for them are the same fact.
     *
     * A failure while building this app's section is caught here rather than
     * left to the library's own containment, which names only the failure's
     * type: the fallback still carries the recent log, which is the diagnostic
     * the report exists for. The earlier runs are still appended, as the
     * library does for a failure it contains itself: they are still there to
     * read, and the crash that prompted the report is what it most needs.
     */
    private fun collectReport(context: Context, fileSink: DebugFileSink?): CollectedReport =
        DebugReport.collect(LauncherDebugLog, fileSink, heading = PREVIOUS_RUN_HEADING) {
            try {
                appSection(context)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                LauncherDebugLog.failure(t, "BugReport payload collection failed")
                buildFallbackPayload(t)
            }
        }

    /**
     * Hands the report to the shared library, which copies it to the clipboard,
     * opens the chooser, and lowers the crash banner once the copy lands.
     *
     * The share can outlive the Activity that started it (a rotation, or the
     * screen torn down while the payload was still building). Starting an
     * activity from a torn-down one targets a dead token, so a finished
     * Activity is swapped for the application context — the library launches
     * the chooser with `NEW_TASK` regardless, so it still opens.
     */
    private fun deliverViaLibrary(activity: Activity, report: CollectedReport, screenshot: Uri?): ShareOutcome {
        val launchContext: Context =
            if (activity.isFinishing || activity.isDestroyed) activity.applicationContext else activity
        return DebugReport.deliver(
            context = launchContext,
            log = LauncherDebugLog,
            report = report,
            subject = "Type Launcher bug report — ${BuildConfig.VERSION_NAME}",
            chooserTitle = activity.getString(R.string.bug_report_chooser_title),
            clipboardLabel = "Type Launcher bug report",
            screenshot = screenshot,
        )
    }

    /**
     * The report to fall back on when collecting the real one threw. Deliberately
     * tiny and dependency-free — it reads nothing off disk, because a disk read is
     * what most plausibly just failed.
     */
    private fun buildFallbackPayload(failure: Throwable): String = buildString {
        appendLine("Type Launcher bug report")
        appendLine("Version: ${BuildConfig.VERSION_NAME}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        // The message is clipped: an exception can carry a whole serialized value
        // or file dump, and an unbounded fallback would blow the very ceiling
        // this path exists to stay under — on the one path that runs when the
        // normal report couldn't even be built.
        val why = failure.message?.take(MAX_FAILURE_MESSAGE_CHARS)
        appendLine("Report collection failed: ${failure.javaClass.name}" + (why?.let { ": $it" } ?: ""))
        // The recent log is the diagnostic the report exists for, it is already
        // in memory, and the failure was recorded into it just above. Without it
        // the fallback is a four-line report that says nothing about what went
        // wrong — and this path only runs when something already has. The
        // pinned lines come with it, in the same read: how the run started is
        // the context least likely to still be in the ring buffer.
        val logs = runCatching { reportLogLines(DebugReport.APP_SECTION_CHARS - FALLBACK_HEAD_CHARS) }
            .getOrDefault(emptyList())
        append(renderLog(logs))
    }

    /** Tells the user a share reached neither the chooser nor the clipboard. */
    private fun notifyShareFailed(context: Context) {
        // Logged, not swallowed: this is the last user-visible fallback after
        // both delivery routes already failed, so if it throws too the tap does
        // nothing at all — and the log is then the only place that can say why.
        runCatching {
            Toast.makeText(context, R.string.bug_report_share_failed, Toast.LENGTH_LONG).show()
        }.onFailure { LauncherDebugLog.failure(it, "BugReport share-failed notice could not be shown") }
    }

    private fun appSection(context: Context): String {
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
            zoneId = ZoneId.systemDefault(),
            isDockEnabled = dockSettings.isDockEnabled,
            appListLayout = dockSettings.appListLayout,
            dockIconSizeDp = dockSettings.dockIconSizeDp,
            appListSortOrder = dockSettings.appListSortOrder,
            isAgendaEnabled = dockSettings.isAgendaEnabled,
            otherSettings = bugReportSettingRows(dockSettings),
            dockedAppIds = dockedApps,
            widgetPages = widgetStore.widgetPages,
            homeWidgetIds = widgetStore.homeWidgetIds,
            widgetProviders = widgetProviderLines(context, widgetStore),
            // Read last, after the rest of the section has been measured, so
            // the log gets exactly the room the rest left — one read, ordered
            // and budgeted by the library: the ring's kept tail with the pinned
            // lines it no longer holds prepended (Codex on PR #689).
            log = ::reportLogLines,
            iconCache = AppIconLoader.cacheStats(),
            appListChanges = appListEvents.lines(),
        )
    }

    /**
     * Each tracked widget's provider, as the platform has it bound now — which
     * is what decides what the widget shows — beside the provider the launcher
     * remembered at add time where the two differ. Both name the profile kind
     * as well as the component, so a work widget a restore bound to the
     * personal copy of the same provider shows as the mismatch it is. Runs on
     * the collection path (off the main thread): a `getAppWidgetInfo` IPC and a
     * profile-kind lookup per widget.
     */
    private fun widgetProviderLines(context: Context, widgetStore: WidgetStore): Map<Int, String> {
        val manager = AppWidgetManager.getInstance(context)
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val personalUser = Process.myUserHandle()
        return widgetStore.widgetIds.associateWith { id ->
            val remembered = widgetStore.providerRecord(id)?.let { record ->
                widgetIdentity(record.component.flattenToShortString(), record.profileKind)
            }
            val bound = try {
                manager?.getAppWidgetInfo(id)?.let { info ->
                    widgetIdentity(
                        info.provider.flattenToShortString(),
                        launcherApps.widgetProfileKind(info.profile, personalUser),
                    )
                }
            } catch (exception: RuntimeException) {
                // A Binder or profile transition: say the lookup failed rather
                // than reporting a binding state nobody observed.
                LauncherDebugLog.failure(exception, "BugReport provider lookup failed id=%s", id)
                return@associateWith describeWidgetProvider(bound = null, remembered = remembered, lookupFailed = true)
            }
            describeWidgetProvider(bound, remembered)
        }
    }

    private suspend fun captureAndPersistScreenshot(activity: Activity): Uri? {
        // The share runs on the application scope, so it can outlive the screen
        // that started it (a rotation, or the activity being torn down while the
        // payload is still building). A destroyed window has nothing worth
        // capturing, so go straight to a text-only report instead of spending a
        // 10-30 MB buffer finding that out.
        if (activity.isFinishing || activity.isDestroyed) return null
        // ReportScreenshot.capture is the shared, hardened capture (window
        // PixelCopy, off-main buffer, age-based prune, recycle). It blocks, so
        // run it off the main thread; it returns the persisted PNG, or null on
        // any failure — a text-only report, never a crash.
        return withContext(Dispatchers.IO) {
            val file = ReportScreenshot.capture(
                activity,
                File(activity.cacheDir, SCREENSHOT_DIR_NAME),
                LauncherDebugLog,
            ) ?: return@withContext null
            // The FileProvider, its authority, and @xml/file_paths stay app-side
            // — a screenshot is the app's content, the same split DebugReport
            // already follows.
            bugReportScreenshotUri(file, LauncherDebugLog) {
                FileProvider.getUriForFile(
                    activity,
                    activity.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX,
                    it,
                )
            }
        }
    }

    /**
     * Mints the shareable `content://` URI from a captured PNG, guarded. A
     * `FileProvider` misconfiguration (a path outside `@xml/file_paths`) throws
     * `IllegalArgumentException`, and this share runs in the application scope
     * where an escaping throwable would take the launcher down — the one thing a
     * bug-report path must never do. On failure it degrades to a text-only
     * report (null) and drops the now-unshareable PNG; [CancellationException]
     * propagates. The mint is injected so a plain-JVM test can drive both paths
     * without a device (`BugReportScreenshotUriTest`).
     */
    internal fun bugReportScreenshotUri(file: File, log: DebugLog, mint: (File) -> Uri): Uri? =
        try {
            mint(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.failure(e, "bug report: screenshot URI could not be built")
            file.delete()
            null
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

/**
 * Every user setting [buildBugReportPayload] doesn't already name, as
 * `label to value` rows for the report's "--- Settings ---" section, so a
 * report shows the whole configuration the bug happened under (e.g. whether
 * the keyboard auto-shows, or Home's widgets are on). Choices, toggles, and
 * numbers only — the same privacy floor as [launcherTelemetryKeys]. Reads
 * SharedPreferences, so call it off the main thread.
 */
internal fun bugReportSettingRows(settings: DockSettingsStore): List<Pair<String, String>> {
    val reservation = settings.keyboardReservation
    return listOf(
        "Work dock enabled" to settings.isWorkDockEnabled.toString(),
        "Dock layout" to settings.dockLayout.name,
        "Landscape dock position" to settings.landscapeDockPosition.name,
        "Keyboard auto-shown" to settings.isKeyboardAutoShown.toString(),
        "Keyboard floating" to settings.isKeyboardFloating.toString(),
        "Keyboard reservation" to "${reservation.bottomPx}px (${reservation.source.name})",
        "Wallpaper shown" to settings.isWallpaperShown.toString(),
        "Home widgets shown" to settings.isHomeWidgetsShown.toString(),
        "Kiosk mode" to settings.isKioskMode.toString(),
        "Kiosk lock" to settings.isKioskLock.toString(),
        "Kiosk dim when idle" to settings.isKioskDimWhenIdle.toString(),
        "Kiosk blank at night" to settings.isKioskBlankAtNight.toString(),
        "Kiosk blank from" to kioskClockTime(settings.kioskBlankStartMinutes),
        "Kiosk blank until" to kioskClockTime(settings.kioskBlankEndMinutes),
        "Kiosk blank when idle" to settings.isKioskBlankWhenIdle.toString(),
        "Kiosk wake on light" to settings.isKioskWakeOnLight.toString(),
        "Kiosk light wake level" to "${settings.kioskLightWakeLux.roundToInt()} lux",
        "Contact search enabled" to settings.isContactSearchEnabled.toString(),
        "Calendar search enabled" to settings.isCalendarSearchEnabled.toString(),
        "Call method" to settings.callMethod.name,
        "Theme" to settings.themeMode.name,
        "Icon shape" to settings.iconShape.name,
        "Icon theme" to settings.iconTheme.name,
        // The effective choice, consent gate included — the raw flag can read
        // true on an install that never answered the consent question.
        "Analytics enabled" to (StoredTelemetryPreferences(settings).isEnabled()?.toString() ?: "unreadable"),
    )
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
    zoneId: ZoneId,
    isDockEnabled: Boolean,
    appListLayout: AppListLayout,
    dockIconSizeDp: Int,
    appListSortOrder: AppListSortOrder,
    isAgendaEnabled: Boolean,
    dockedAppIds: List<String>,
    widgetPages: List<List<Int>>,
    log: (budgetChars: Int) -> List<String>,
    otherSettings: List<Pair<String, String>> = emptyList(),
    homeWidgetIds: List<Int> = emptyList(),
    widgetProviders: Map<Int, String> = emptyMap(),
    iconCache: AppIconLoader.CacheStats? = null,
    appListChanges: List<String> = emptyList(),
): String {
    val widgetIds = widgetPages.flatten()
    val timestamp = formatLogTimestamp(nowMillis, zoneId)
    val head = buildString {
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
        // Named because every timestamp below — the capture time, and every
        // log line — is rendered in it. The offset each line carries says what
        // the clock read; the zone says which rules produced it, which is what
        // makes a DST step or a mid-log zone change reconstructible rather than
        // just visible.
        appendLine("Time zone: ${zoneId.id}")
        appendLine()
        appendLine("--- Settings ---")
        appendLine("Dock enabled: $isDockEnabled")
        appendLine("App list layout: $appListLayout")
        appendLine("Dock icon size: ${dockIconSizeDp}dp")
        appendLine("App list sort order: $appListSortOrder")
        appendLine("Agenda enabled: $isAgendaEnabled")
        otherSettings.forEach { (name, value) -> appendLine("$name: $value") }
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
        appendLine("Home widgets (${homeWidgetIds.size}): ${if (homeWidgetIds.isEmpty()) "(none)" else homeWidgetIds.joinToString()}")
        if (widgetProviders.isNotEmpty()) {
            appendLine("Widget providers:")
            widgetProviders.forEach { (id, provider) -> appendLine("  $id: $provider") }
        }
        // Read live at capture rather than recovered from the log: the counters
        // used to be flushed into the ring buffer every 50 lookups, which
        // dominated it and evicted the very context the report is read for.
        if (iconCache != null) {
            appendLine()
            appendLine("--- Icon cache ---")
            appendLine("Entries: ${iconCache.entries} (${iconCache.bytes} bytes)")
            appendLine("Lookups: ${iconCache.hits} hits, ${iconCache.misses} misses")
        }
    }
    // Cap the structured section on its own, then always append the newest log
    // after it. Prefix-truncating the whole section would drop the recent log —
    // appended last — exactly when a long docked-app or widget list is what
    // pushed it over, losing the diagnostic the report exists for. The library
    // would cut an oversized section from the middle instead; budgeting each
    // part here means it never has to choose for us.
    val boundedHead = if (head.length > MAX_STRUCTURED_CHARS) {
        head.take(MAX_STRUCTURED_CHARS) + "\n…(details truncated to keep the report shareable)\n"
    } else {
        head
    }
    // Its own bounded section, ahead of the log the routine lines fill: an
    // install and the reload it caused are what "the app I installed isn't
    // listed" needs, and the log has usually dropped them by the time it's
    // reported. See [AppListEventLog].
    val changes = buildString {
        appendLine()
        val kept = boundedLogTail(appListChanges, MAX_APP_LIST_CHANGES_CHARS)
        appendLine("--- App list changes (${kept.size}, newest last) ---")
        if (kept.isEmpty()) {
            appendLine("(none since the launcher started)")
        } else {
            kept.forEach { appendLine(it) }
        }
    }
    // The log takes whatever the rest of the section left of the app's share,
    // so a short settings dump hands its unused room to the log rather than
    // leaving it empty. The earlier runs are the library's, appended after.
    val logBudget = DebugReport.APP_SECTION_CHARS - boundedHead.length - changes.length - LOG_HEADING_CHARS
    return boundedHead + changes + renderLog(log(logBudget))
}

/**
 * The "Log" section — one chronological run of lines, newest last, already
 * bounded by [reportLogLines]. Shared with the collection-failure fallback
 * ([BugReport]), which needs it most: a fallback report with no log says
 * nothing at all.
 *
 * One section, not two: the log restores the pinned lines it has since evicted
 * ahead of its kept tail, in order and without duplication, so the startup
 * context and the recent run read as the single sequence they were.
 *
 * **The heading counts what is here and claims nothing about what is not.** It
 * used to read "N of M shown", with M the size of the list handed in — which
 * was the whole ring back when this function did the only bounding. It is not
 * any more: the log is bounded upstream, so M arrived already equal to N and
 * the heading read "120 of 120 shown" on a report that had dropped 180 lines
 * — a report claiming to be complete precisely when it was not (Codex on PR
 * #706). The count of what was dropped is not knowable here, so the heading
 * states the bound instead of a total it cannot have.
 */
private fun renderLog(kept: List<String>): String = buildString {
    appendLine()
    appendLine("--- Log (${kept.size} lines, newest last; older lines are dropped to keep the report shareable) ---")
    if (kept.isEmpty()) {
        appendLine("(no captured log lines)")
    } else {
        kept.forEach { appendLine(it) }
    }
}

/**
 * The ceiling a whole shared report stays under: the fleet's one report
 * ceiling, androidlog's [DebugReport.MAX_REPORT_CHARS], set by what survives
 * being pasted whole. The library holds every report to it and gives this app
 * [DebugReport.APP_SECTION_CHARS] of it; the earlier runs it appends take the
 * rest.
 */
internal const val MAX_SHARE_PAYLOAD_CHARS = DebugReport.MAX_REPORT_CHARS

/** The earlier runs' heading, which the library writes above the runs it appends. */
internal const val PREVIOUS_RUN_HEADING = "--- Previous run (ended without a clean exit) ---"

/** Room for the log's heading and the blank line before it: the heading's count runs to a few digits. */
private const val LOG_HEADING_CHARS = 120

/** Ceiling for the app-list changes section: about fifteen lines. */
private const val MAX_APP_LIST_CHANGES_CHARS = 1_200

/**
 * Room for the launcher's own pinned lines — the home-resolution and
 * home-start markers, a few short lines — on top of the process-exit batch the
 * library reserves for. The pinned section is trimmed from its head, so a
 * reserve too small for both would cut the oldest exits.
 */
private const val LAUNCHER_PINNED_CHARS = 800

/**
 * The least of the recent log the structured section may leave: a settings dump
 * reads fine truncated, a log with no recent lines explains nothing.
 */
private const val MIN_RECENT_LOG_CHARS = 500

/** The fallback's own lines ahead of its log: version, Android, and a clipped failure message. */
private const val FALLBACK_HEAD_CHARS = 600

/** The pinned lines' reserve in the log: a whole process-exit batch, plus the launcher's own. */
private val pinnedReserveChars: Int get() = ProcessExits.maxBatchChars() + LAUNCHER_PINNED_CHARS

/**
 * This run's log as both report paths read it, within [budgetChars]: the kept
 * tail, preceded by the pinned lines it evicted (why earlier processes ended,
 * how Home resolved), whose reserve is held first and handed on to the recent
 * lines where they don't need it.
 */
internal fun reportLogLines(budgetChars: Int = DebugReport.APP_SECTION_CHARS): List<String> =
    DebugReport.logLines(LauncherDebugLog, budgetChars, pinnedReserveChars)

/** Cap for an exception message quoted into the collection-failure fallback. */
private const val MAX_FAILURE_MESSAGE_CHARS = 300

/**
 * Ceiling for the structured section (build/device/settings/docked apps/widgets),
 * bounded separately from the log so a huge docked-app list can't crowd the log
 * out. What is left of the app's share once the log's pinned reserve, a minimum
 * of recent lines, the app-list changes and the headings are set aside: it is
 * the section that degrades most gracefully — a settings dump reads fine
 * truncated, a truncated log tail loses events.
 */
private val MAX_STRUCTURED_CHARS: Int by lazy {
    DebugReport.APP_SECTION_CHARS - pinnedReserveChars - MIN_RECENT_LOG_CHARS -
        MAX_APP_LIST_CHANGES_CHARS - LOG_HEADING_CHARS - STRUCTURED_FRAMING_CHARS
}

/** The truncation notice and the app-list changes' heading and blank line. */
private const val STRUCTURED_FRAMING_CHARS = 200

/**
 * A provider as the report names it: the flattened [component], plus the
 * profile [kind] when it is anything but the personal user's. An unknown kind
 * (a profile that couldn't be classified, or a record written before kinds
 * existed) says so rather than reading as personal, which would hide a
 * cross-profile misbinding behind a clean-looking match.
 */
internal fun widgetIdentity(component: String, kind: WidgetProfileKind?): String = when (kind) {
    WidgetProfileKind.PERSONAL -> component
    null -> "$component [profile unknown]"
    else -> "$component [${kind.token}]"
}

/**
 * One widget's line in the report: the [bound] provider, with the
 * [remembered] one alongside when they differ; "unbound" when the platform
 * has no binding for the ID, or "lookup failed" when asking it threw.
 */
internal fun describeWidgetProvider(bound: String?, remembered: String?, lookupFailed: Boolean = false): String {
    val rememberedSuffix = remembered?.let { " (remembered $it)" } ?: ""
    return when {
        lookupFailed -> "lookup failed$rememberedSuffix"
        bound == null -> "unbound$rememberedSuffix"
        remembered == null || remembered == bound -> bound
        else -> "$bound$rememberedSuffix"
    }
}

/** A minute of the day as 24-hour "HH:mm", whatever the device's own format. */
internal fun kioskClockTime(minuteOfDay: Int): String =
    "%02d:%02d".format(java.util.Locale.ROOT, minuteOfDay / 60, minuteOfDay % 60)
