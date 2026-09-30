package app.typelauncher

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.android.DebugFileSink
import com.mikelward.androidlog.android.DebugReport
import com.mikelward.androidlog.android.PreviousRun
import com.mikelward.androidlog.android.ReportScreenshot
import com.mikelward.androidlog.boundedLogTail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.util.Locale

private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"
private const val SCREENSHOT_DIR_NAME = "bug-reports"

/**
 * Builds a paste-into-an-issue bug-report payload (build/device info, the
 * launcher's persisted settings, and the in-memory log buffer) and hands it
 * off via [Intent.ACTION_SEND] so the share sheet can deliver it. Also drops
 * the text on the clipboard as a paste fallback.
 */
/**
 * What one collection produced: the report text, and the prior-run handle it
 * was built from.
 *
 * The handle travels with the text rather than staying on the sink, and that
 * pairing is the whole point: [DebugFileSink.clearPreviousRun] consumes the
 * handle this report was built from, so a share deletes exactly the runs it
 * carried. When the files lived in one slot on the sink, an overlapping share
 * could consume whatever the *latest* read had surfaced instead — destroying a
 * run only the other attempt had read, and whose own hand-off might still fail
 * (Codex, PR #707 finding 4).
 *
 * Null [previousRun] means there was nothing to send, or the read could not be
 * completed. Either way there is nothing this report may delete.
 */
internal class CollectedReport(val text: String, val previousRun: PreviousRun?)

internal object BugReport {
    /**
     * Captures the screen, builds the text payload, copies the text to the
     * clipboard, and fires the share-sheet chooser. [includeScreenshot] = false
     * (or a capture failure) shares text only.
     *
     * [mainDispatcher], [payloadCollect], [screenshotCapture], [clipboardWrite],
     * and [chooserLaunch] are injectable test seams (production uses the
     * defaults): each delivery route can fail on its own, and the conditional
     * clear and the failure notice below are behavior a test must be able to
     * drive every way — clipboard lands vs. fails, chooser opens vs. doesn't —
     * without a real window, `ClipboardManager`, or share target
     * (`BugReportShareTest`).
     */
    suspend fun share(
        activity: Activity,
        includeScreenshot: Boolean = true,
        mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
        payloadCollect: (Context, DebugFileSink?) -> CollectedReport = ::collectPayload,
        screenshotCapture: suspend (Activity) -> Uri? = ::captureAndPersistScreenshot,
        clipboardWrite: (Context, String) -> Boolean = ::copyToClipboard,
        chooserLaunch: (Activity, String, Uri?) -> Boolean = ::startShare,
    ) {
        val fileSink = (activity.applicationContext as? TypeLauncherApp)?.debugFileSink
        // Build the payload off the main thread: it reads persisted settings and
        // up to a few prior-run log files, and share() normally runs in a
        // main-thread UI scope (Codex on PR #592).
        val collected = try {
            withContext(Dispatchers.IO) { payloadCollect(activity, fileSink) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // A report is most useful after something has already gone wrong.
            // Never turn a failure while inspecting that state into another app
            // crash — this runs from a UI tap, where an escaping throwable takes
            // the launcher down. Retain a small shareable diagnostic instead.
            //
            // No handle: the fallback carries no prior run, so it must never
            // consume one. That used to need a `carriesPriorRun` flag; now it is
            // the absence of the receipt, which cannot fall out of step with
            // what the report actually contains.
            LauncherDebugLog.failure(t, "BugReport payload collection failed")
            CollectedReport(buildFallbackPayload(t), previousRun = null)
        }
        val text = collected.text
        // The clipboard and chooser hand-off touch the Activity, so pin them to
        // the main thread: the payload build above hops to IO, and its
        // continuation must not leave this on a worker thread. The screenshot
        // capture runs off the main thread itself (inside ReportScreenshot,
        // which hops to the main thread only to read the window geometry), so
        // calling it from here suspends this block to IO and resumes on the main
        // thread for the hand-off.
        val clipboardOk = withContext(mainDispatcher) {
            val screenshotUri: Uri? = if (includeScreenshot) screenshotCapture(activity) else null
            // Guarded like the chooser below: both are injectable seams, and
            // share() runs in a caller's coroutine scope where an escaping
            // throwable would take the app down with it — the one thing a
            // bug-report path must never do.
            // Each logs what it caught: these guards also cover the work *around*
            // the inner logged ones (building the chooser intent, resolving the
            // clipboard service), so without this the user could see only the
            // generic toast while the log said nothing about why.
            val copied = runCatching { clipboardWrite(activity, text) }
                .onFailure { LauncherDebugLog.failure(it, "BugReport clipboard hand-off threw") }
                .getOrDefault(false)
            // Fire the chooser for its side effect; its launch is not proof of
            // delivery (no ACTION_SEND completion callback), so it doesn't gate the
            // clear below — only the retained clipboard copy does.
            val launched = runCatching { chooserLaunch(activity, text, screenshotUri) }
                .onFailure { LauncherDebugLog.failure(it, "BugReport chooser hand-off threw") }
                .getOrDefault(false)
            // Neither route landed: the tap would otherwise do nothing visible at
            // all — no chooser, nothing on the clipboard — and the user would
            // retry into the same silence. Say so instead.
            if (!copied && !launched) notifyShareFailed(activity)
            copied
        }
        // Clear the prior run only once the report is *retained* somewhere the
        // user can still get it — i.e. the clipboard copy landed. `ACTION_SEND`
        // gives no delivery/selection callback, so a launched chooser is not
        // proof the report was sent (the user can back out of the sheet); the
        // clipboard copy is the durable fallback that survives that. Gating on it
        // (not "chooser launched") means a failed clipboard copy paired with a
        // canceled sheet keeps the crash log for the next attempt instead of
        // losing it, and keeps this in step with what the post-crash banner
        // promises (Codex on PR #592 / #593). If the scope is canceled earlier (a
        // config change while the screenshot capture is suspended) or the copy
        // fails, the files survive. The fallback payload carries no prior run at
        // all, so it must never consume one: deleting the crash log the user
        // tapped Share for, in favor of a report that doesn't contain it, would
        // destroy the only copy.
        val consumed = collected.previousRun
        if (consumed != null && clipboardOk) {
            withContext(Dispatchers.IO) { fileSink?.clearPreviousRun(consumed) }
        }
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
        // in memory, and share() recorded the failure into it just above. Without
        // it the fallback is a four-line report that says nothing about what went
        // wrong — and this path only runs when something already has.
        // The pinned lines come with it, in the same read: this path runs only
        // when collection already failed, and how the run started is the context
        // least likely to still be in the ring buffer.
        val logs = runCatching { reportLogLines() }.getOrDefault(emptyList())
        append(renderLog(logs, MAX_LOG_PAYLOAD_CHARS + MAX_PINNED_PAYLOAD_CHARS))
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

    private fun collectPayload(context: Context, fileSink: DebugFileSink?): CollectedReport {
        // One read, ordered and budgeted by the library: the ring's kept tail
        // with the pinned lines it no longer holds prepended. Reading the two
        // buffers separately is what let a line land in between and be filed as
        // older than lines it was newer than (Codex on PR #689).
        val log = reportLogLines()
        val dockSettings = DockSettingsStore(context)
        val dockedApps = DockedAppStore(context).dockedAppIds
        val widgetStore = WidgetStore(context)
        // The previous run's handle if one ended without a clean exit (a crash
        // or a silent kill). Read here (on Dispatchers.IO via share()) and
        // carried out with the text; share() consumes it only after the
        // hand-off, so a cancellation can't lose the run.
        val previousRun = fileSink?.let(::readPreviousRunForReport)
        val text = buildBugReportPayload(
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
            dockedAppIds = dockedApps,
            widgetPages = widgetStore.widgetPages,
            log = log,
            iconCache = AppIconLoader.cacheStats(),
            previousRun = previousRun?.text,
        )
        return CollectedReport(text, previousRun)
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

    private fun startShare(activity: Activity, text: String, screenshotUri: Uri?): Boolean {
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
        // The share can outlive the Activity that started it (see the capture
        // above). Starting an activity from a torn-down one targets a dead
        // token, so launch from the application context with NEW_TASK instead —
        // the chooser still opens, which is the whole point of not tying the
        // share to the screen.
        val launchContext: Context = if (activity.isFinishing || activity.isDestroyed) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.applicationContext
        } else {
            activity
        }
        // Returns whether the chooser actually launched: the caller clears the
        // prior-run diagnostics only once the report has reached the user somehow.
        return runCatching { launchContext.startActivity(chooser); true }
            .onFailure { LauncherDebugLog.failure(it, "BugReport.share intent failed") }
            .getOrDefault(false)
    }

    /** Returns whether the copy landed; the caller uses it to decide whether to clear. */
    private fun copyToClipboard(context: Context, text: String): Boolean =
        runCatching {
            val cm = context.getSystemService(ClipboardManager::class.java) ?: return@runCatching false
            cm.setPrimaryClip(ClipData.newPlainText("Type Launcher bug report", text))
            true
        }.onFailure { LauncherDebugLog.failure(it, "BugReport.clipboard copy failed") }
            .getOrDefault(false)
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
    zoneId: ZoneId,
    isDockEnabled: Boolean,
    appListLayout: AppListLayout,
    dockIconSizeDp: Int,
    appListSortOrder: AppListSortOrder,
    isAgendaEnabled: Boolean,
    dockedAppIds: List<String>,
    widgetPages: List<List<Int>>,
    log: List<String>,
    previousRun: String? = null,
    iconCache: AppIconLoader.CacheStats? = null,
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
    // Cap the structured section on its own, then always append the (already
    // bounded) newest log after it. Prefix-truncating the whole report would drop
    // the recent log — appended last — exactly when a long docked-app or widget
    // list is what pushed it over the limit, losing the diagnostic the report
    // exists for. Both parts are bounded, so the concatenation is too: strings
    // parcel as UTF-16, so this keeps the clipboard / ACTION_SEND payload well
    // under the ~1 MB Binder limit instead of failing silently.
    val boundedHead = if (head.length > MAX_STRUCTURED_CHARS) {
        head.take(MAX_STRUCTURED_CHARS) + "\n…(details truncated to keep the report shareable)\n"
    } else {
        head
    }
    // The previous run's log if it didn't exit cleanly — its own bounded
    // section between the settings and the current run's log, so the crash or
    // kill that ended the last run is right there. Keep the NEWEST lines: the
    // file is oldest-first, so the crash entry and last events are at the end.
    val crash = if (previousRun.isNullOrBlank()) {
        ""
    } else {
        buildString {
            appendLine()
            appendLine("--- Previous run (ended without a clean exit) ---")
            appendLine(
                boundedLogTail(previousRun.trimEnd().split("\n"), MAX_CRASH_PAYLOAD_CHARS)
                    .joinToString("\n"),
            )
        }
    }
    return boundedHead + crash +
        renderLog(log, MAX_LOG_PAYLOAD_CHARS + MAX_PINNED_PAYLOAD_CHARS)
}

/**
 * The "Log" section — one chronological run of lines, newest last, bounded to
 * [budgetChars]. Shared with the collection-failure fallback ([BugReport]),
 * which needs it most: a fallback report with no log says nothing at all.
 *
 * One section, not two: the log restores the pinned lines it has since evicted
 * ahead of its kept tail, in order and without duplication, so the startup
 * context and the recent run read as the single sequence they were.
 *
 * **The heading counts what is here and claims nothing about what is not.** It
 * used to read "N of M shown", with M the size of the list handed in — which
 * was the whole ring back when this function did the only bounding. It is not
 * any more: `boundedSnapshot` bounds first, so M arrives already equal to N and
 * the heading read "120 of 120 shown" on a report that had dropped 180 lines
 * upstream — a report claiming to be complete precisely when it was not (Codex
 * on PR #706). The count of what was dropped is not knowable here, so the
 * heading states the bound instead of a total it cannot have.
 */
private fun renderLog(log: List<String>, budgetChars: Int): String = buildString {
    appendLine()
    val kept = boundedLogTail(log, budgetChars)
    appendLine("--- Log (${kept.size} lines, newest last; older lines are dropped to keep the report shareable) ---")
    if (kept.isEmpty()) {
        appendLine("(no captured log lines)")
    } else {
        kept.forEach { appendLine(it) }
    }
}

/**
 * The ceiling a whole shared report stays under.
 *
 * Strings parcel as UTF-16, so N characters cost 2N bytes on the wire, and the
 * payload crosses Binder twice — into the clipboard, then again in the chooser's
 * `ACTION_SEND` extra. The per-process Binder buffer is ~1 MB **shared** across
 * every in-flight transaction, so an unbounded report threw
 * `TransactionTooLargeException` at both ends, and since both are best-effort the
 * tap did nothing whatsoever — no chooser, nothing on the clipboard.
 *
 * The chooser is the tighter of the two routes: starting it copies a text-only
 * share's `EXTRA_TEXT` into the intent's `ClipData`, so the text rides in one
 * transaction twice, four bytes a character. This is the fleet's one report
 * ceiling, androidlog's [DebugReport.MAX_REPORT_CHARS] — about 240 KB there,
 * where a report of 160,000 characters (this app's old ceiling) is 640 KB.
 *
 * The section budgets below add up to 58,000; the slack covers the section
 * headers and the one over-budget line each section may keep (a single line is
 * itself capped by the log's own per-entry ceiling).
 */
internal const val MAX_SHARE_PAYLOAD_CHARS = DebugReport.MAX_REPORT_CHARS

/** Ceiling for the current run's log — ~40 KB of UTF-16 on the wire. */
private const val MAX_LOG_PAYLOAD_CHARS = 20_000

/**
 * Ceiling for the pinned lines the log restores ahead of its kept tail.
 *
 * Small because they are: a couple of dozen short lines written once each at
 * startup, a few hundred characters in total in practice. It is capped anyway
 * rather than left to the entry count alone, so the section can't put the whole
 * report over the share ceiling. It must hold a whole process-exit batch
 * (`ProcessExits.maxBatchChars()`, about 2,850) with room left for the
 * launcher's own pinned home-resolution lines: the section is trimmed from its
 * head, so a smaller budget would drop the oldest exits.
 */
private const val MAX_PINNED_PAYLOAD_CHARS = 4_000

/** This run's log as both report paths read it: the kept tail, preceded by the pinned lines it evicted. */
internal fun reportLogLines(): List<String> = LauncherDebugLog.boundedSnapshot(
    pinnedBudgetChars = MAX_PINNED_PAYLOAD_CHARS,
    recentBudgetChars = MAX_LOG_PAYLOAD_CHARS,
)

/**
 * Ceiling for the previous-run section, the same share androidlog gives the
 * earlier runs in a report it builds itself. Bounded again when the report is
 * put together, so the sections together — structured head, crash, current
 * log — stay inside [MAX_SHARE_PAYLOAD_CHARS] whatever text they are handed.
 */
private const val MAX_CRASH_PAYLOAD_CHARS = DebugReport.MAX_EARLIER_RUNS_CHARS

/**
 * The earlier runs for a report, read at [MAX_CRASH_PAYLOAD_CHARS].
 *
 * Bounded in the read, not only when the section is rendered: the handle
 * names the files its text carries, and [DebugFileSink.clearPreviousRun]
 * deletes exactly those. Read whole and trimmed afterwards, the handle still
 * named every run the trim cut away, and sharing the report deleted them
 * unsent.
 */
internal fun readPreviousRunForReport(sink: DebugFileSink): PreviousRun? =
    // One under the section's bound: the section's own trim charges each line
    // a newline, the last included, and a text read to the full bound could
    // lose its oldest line there after its file was already consumed.
    sink.readPreviousRun(MAX_CRASH_PAYLOAD_CHARS - 1)

/** Cap for an exception message quoted into the collection-failure fallback. */
private const val MAX_FAILURE_MESSAGE_CHARS = 300

/**
 * Ceiling for the structured section (build/device/settings/docked apps/widgets),
 * bounded separately from the log so a huge docked-app list can't crowd the log
 * out. The smallest of the three: it is the section that degrades most gracefully
 * — a settings dump reads fine truncated, a truncated log tail loses events.
 */
private const val MAX_STRUCTURED_CHARS = 14_000
