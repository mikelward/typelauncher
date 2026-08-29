package app.typelauncher

import android.content.Context
import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Current run's persisted log; renamed to a [PREVIOUS_PREFIX] file at the next start. */
private const val CURRENT_FILE = "debug.log"

/** Prefix for prior runs' logs, one file per run, surfaced then deleted by the report. */
private const val PREVIOUS_PREFIX = "debug-prev-"

/** Suffix for a prior run that ended gracefully or by a silent kill (no crash). */
private const val PREVIOUS_PLAIN_SUFFIX = ".log"

/**
 * Suffix marking a prior run that ended in an *uncaught exception* — a real
 * crash, as opposed to a routine kill (OS reclaim, force-stop, app update). Only
 * these raise the post-crash banner, so it is never shown for an ordinary
 * process death. Both kinds of prior run are still readable/shareable
 * ([readPreviousRuns]); the suffix only gates the *banner*.
 */
private const val PREVIOUS_CRASH_SUFFIX = ".crash.log"

/**
 * Suffix for a run that crashed and whose crash the user has already seen —
 * dismissed, or delivered by a report that could not carry the whole run. It
 * keeps the crash *classification* while no longer raising the banner.
 *
 * Acknowledgement used to rename straight to [PREVIOUS_PLAIN_SUFFIX], which was
 * harmless while the suffix only gated the banner. It stopped being harmless
 * once the report began labeling each section: the suffix is the only persisted
 * record that a run crashed, so an acknowledged crash came back in a later
 * report as "ended without a clean exit, no crash recorded" — contradicting its
 * own contents, which still hold the uncaught exception.
 */
private const val PREVIOUS_ACKNOWLEDGED_CRASH_SUFFIX = ".crash-seen.log"

/**
 * Companion to [CURRENT_FILE], written by the uncaught-exception handler and
 * consumed at the next start. Its presence there is the one reliable in-process
 * signal that this run crashed rather than exited or was killed; a graceful run
 * never creates it. Kept out of [previousFiles] by not matching [PREVIOUS_PREFIX].
 */
private const val CRASH_MARKER_FILE = "$CURRENT_FILE.crash"

/** How many unshared prior runs to keep — older ones are dropped at startup. */
private const val MAX_PREVIOUS_RUNS = 5

/**
 * Bound on the persisted content. The in-memory buffer is already count-bounded,
 * so mirroring it caps each file too; this is a belt-and-suspenders ceiling.
 */
private const val PERSIST_BUDGET_CHARS = 150_000

/**
 * How long the crash handler waits for the final flush (queued behind any
 * in-flight write) before chaining on. Long enough to land the crash snapshot on
 * healthy storage, short enough that a stalled disk never delays Crashlytics or
 * process termination.
 */
private const val CRASH_WRITE_TIMEOUT_MS = 250L

/**
 * Debounce window for continuous-mirror writes. Every persisted write is a full
 * replacement of the (bounded) buffer, and the launcher logs on frequent events
 * — key-down/up while typing, state transitions — so writing per event would
 * rewrite the whole file dozens of times a second. Coalescing over this window
 * bounds that to a couple of writes a second while still guaranteeing a trailing
 * write. A silent kill can lose at most this much tail from the mirror; a crash
 * doesn't, because the crash handler flushes explicitly (Codex on PR #592).
 */
private const val WRITE_DEBOUNCE_MS = 500L

/**
 * One prior run's persisted log, as handed to the bug report.
 *
 * [id] is the sink's own file name — opaque to the report, and the token it
 * hands back to [DebugFileSink.consumePreviousRuns] to delete exactly the runs
 * a report carried. [crashed] distinguishes a run that ended in an uncaught
 * exception from one that ended in a routine kill, which the report has to be
 * able to say out loud: both used to render under the same "ended without a
 * clean exit" heading, so a user holding a crash report could not tell whether
 * anything had actually crashed.
 */
internal data class PreviousRunLog(
    /**
     * The run's rotation stem, *without* the suffix that records how it ended.
     * Deliberately not the file name: acknowledging a crash renames the file, so
     * a share whose payload captured a name could no longer find its own run if
     * anything renamed it in between — a Dismiss tapped mid-share did exactly
     * that, and the fully-carried run then survived to be reported again.
     */
    val id: String,
    val crashed: Boolean,
    /**
     * True when [crashed] and the user has already been shown this crash — it
     * has been dismissed, or delivered by an earlier report. Still a crash for
     * labeling, but it must not outrank one nobody has seen when the report
     * spends its budget, or an oversized seen crash would take that budget on
     * every share and an older unseen one could never be reported at all.
     */
    val crashAlreadySeen: Boolean = false,
    val lines: List<String>,
    /**
     * True when [lines] is already short of what the file holds, because the
     * read itself was bounded. Such a run can never be consumed: the report can
     * only compare against what it was given, so "carried in full" would mean
     * "carried all of what we bothered to read".
     */
    val truncatedAtRead: Boolean = false,
)

/**
 * Persists the debug log to app-private files so it survives the process ending
 * — a crash *or* a silent kill (the launcher process can be killed with no
 * visible UI and no uncaught exception, so a crash-only handler would miss it).
 * The current run mirrors the in-memory ring buffer ([LauncherDebugLog]) to
 * [CURRENT_FILE]; at the next start that file is renamed aside under a unique
 * [PREVIOUS_PREFIX] name, so every unshared prior run is kept (several cold
 * starts can happen before the user shares) and none is lost to a later boring
 * run. The bug report ([BugReport]) reads and clears them.
 *
 * A run that ended in an *uncaught exception* is marked (via [CRASH_MARKER_FILE])
 * and rotated to a distinct crash-suffixed name, so the next start can tell a
 * real crash apart from a routine kill and raise the post-crash banner only for
 * the former ([hasUnacknowledgedCrash]); [acknowledgeCrashBanner] dismisses it.
 *
 * **Everything touching the filesystem runs on a single daemon worker** — the
 * startup rotation, the continuous mirroring, and the crash flush — so nothing
 * blocks the main thread on disk (the launcher cold-start path is sacred). The
 * worker is FIFO, so the rotation task enqueued in [start] always runs before
 * this run's writes or the crash flush, preserving "rotate before we clobber the
 * prior run" without a lock. Files live in [dir] (an app-private cache
 * directory, excluded from backup).
 */
internal class DebugFileSink internal constructor(
    dirProvider: () -> File,
    // Debounce for the continuous mirror. 0 in tests so a logged line persists
    // synchronously (deterministic); [WRITE_DEBOUNCE_MS] in production.
    private val writeDebounceMs: Long = 0L,
) : LauncherDebugLog.Sink {

    // Production: resolve cacheDir lazily, so the (possibly dir-creating) I/O of
    // `getCacheDir()` runs on the worker at first use, not on the main thread in
    // the constructor during cold start (Codex on PR #592).
    constructor(context: Context) : this({ context.applicationContext.cacheDir }, WRITE_DEBOUNCE_MS)

    /** Tests / direct use: an already-resolved directory (writes are un-debounced). */
    internal constructor(dir: File) : this({ dir })

    // Resolved on first access, which is always inside a worker task (every file
    // operation runs there), so cacheDir resolution never touches the main thread.
    private val dir: File by lazy(dirProvider)

    private val current get() = File(dir, CURRENT_FILE)
    private val temp get() = File(dir, "$CURRENT_FILE.tmp")
    private val crashMarker get() = File(dir, CRASH_MARKER_FILE)

    /** Prior runs' files, oldest first (by last-modified time). */
    private fun previousFiles(): List<File> =
        (dir.listFiles { file -> file.name.startsWith(PREVIOUS_PREFIX) } ?: emptyArray())
            .sortedBy { it.lastModified() }

    /** Prior runs whose crash the user has not yet seen — the files that raise the banner. */
    private fun crashedFiles(): List<File> =
        previousFiles().filter { it.name.endsWith(PREVIOUS_CRASH_SUFFIX) }

    /**
     * Deletes [file], saying so when it doesn't go. `delete()` reports failure by
     * returning false as readily as by throwing, and swallowing either leaves a
     * file behind with nothing anywhere to say why — whether that is a shared
     * crash still raising its prompt or a retained-run directory quietly growing
     * past its cap. Returns whether it went, so callers can act on the answer.
     * [what] names the operation; no user data — file names here are the sink's
     * own rotation stamps.
     */
    private fun deleteSaying(file: File, what: String): Boolean {
        val outcome = runCatching { file.delete() }
        val thrown = outcome.exceptionOrNull()
        return when {
            thrown != null -> {
                LauncherDebugLog.failure(thrown, "DebugFileSink could not %s; the file stays for the next attempt", safe(what))
                false
            }
            outcome.getOrDefault(false) -> true
            else -> {
                LauncherDebugLog.warning("DebugFileSink could not %s; the file stays for the next attempt", safe(what))
                false
            }
        }
    }

    /** A run's identity across the suffix changes that record how it ended. */
    private fun stemOf(file: File): String = file.name.substringBefore('.')

    /** Whether this run crashed at all, seen or not — what the report labels it by. */
    private fun isCrashed(file: File): Boolean =
        file.name.endsWith(PREVIOUS_CRASH_SUFFIX) || file.name.endsWith(PREVIOUS_ACKNOWLEDGED_CRASH_SUFFIX)

    /**
     * Bounds how many unshared prior runs pile up, **evicting ordinary runs
     * before crashed ones**. A crashed run is the evidence the post-crash banner
     * is offering the user; a run that ended in a routine kill is not. Evicting
     * strictly oldest-first let a handful of boring cold starts push a crash out
     * of the window before the user got round to sharing it — the banner then
     * outlived the log it was offering. Only when crashed runs alone overflow
     * does the oldest crash go.
     */
    private fun evictOldestRuns() {
        val prior = previousFiles()
        val excess = prior.size - MAX_PREVIOUS_RUNS
        if (excess <= 0) return
        // Each group keeps previousFiles()' oldest-first order, so taking from the
        // front of this order drops the least valuable first: an ordinary run,
        // then a crash the user has already seen, and only last a crash still
        // waiting to be reported.
        val unseen = prior.filter { it.name.endsWith(PREVIOUS_CRASH_SUFFIX) }
        val seen = prior.filter { it.name.endsWith(PREVIOUS_ACKNOWLEDGED_CRASH_SUFFIX) }
        val ordinary = prior.filterNot { isCrashed(it) }
        (ordinary + seen + unseen).take(excess).forEach { deleteSaying(it, "evict an old prior run") }
    }

    // Single worker, prestarted so the first call never pays thread creation.
    // Daemon, so it never keeps the process alive. Single-threaded, so every file
    // operation is serialized without an explicit lock; *scheduled* so a debounced
    // write can be enqueued with a delay while a 0-delay task (rotation, crash
    // flush, share read) still runs ahead of it.
    private val worker = ScheduledThreadPoolExecutor(
        1,
    ) { runnable -> Thread(runnable, "typelauncher-debug-log").apply { isDaemon = true } }
        .apply { prestartCoreThread() }

    private val writePending = AtomicBoolean(false)

    /**
     * Enqueues the just-ended run's rotation on the worker and installs a chained
     * crash handler. Call once, early in [TypeLauncherApp.onCreate], and register
     * this as a sink afterward. The rotation is enqueued (not run inline), so
     * `onCreate` does no synchronous disk I/O; the FIFO worker still runs it
     * before any of this run's writes.
     */
    fun start() {
        // Rotate on the worker, never on the calling (main) thread: onCreate is on
        // the cold-start path and must not block on disk (Codex on PR #592). FIFO
        // ordering means this runs before this run's log writes and the crash
        // flush, so the prior run is renamed aside before anything can clobber it.
        runCatching {
            worker.execute {
                runCatching {
                    if (current.exists()) {
                        // Was the just-ended run a crash? The uncaught-exception
                        // handler leaves [crashMarker] behind; a graceful exit or a
                        // routine/silent kill does not. Only a crashed run gets the
                        // [PREVIOUS_CRASH_SUFFIX] that raises the banner, so an
                        // ordinary process death never shows it. A cheap metadata
                        // existence check.
                        val suffix = if (crashMarker.exists()) PREVIOUS_CRASH_SUFFIX else PREVIOUS_PLAIN_SUFFIX
                        current.renameTo(File(dir, "$PREVIOUS_PREFIX${System.nanoTime()}$suffix"))
                        evictOldestRuns()
                    }
                    // Consume the just-read crash marker so it can't mislabel this run.
                    crashMarker.delete()
                    // Discard any leftover temp from a write interrupted mid-flight:
                    // never renamed into place, so by the atomic-write contract it
                    // is uncommitted and possibly partial.
                    temp.delete()
                }
            }
        }
        installCrashHandler()
    }

    override fun log(line: String) {
        // Debounced coalesce: the first line since the last write schedules one
        // write [writeDebounceMs] out; every line until then is already captured by
        // that write's snapshot read, so none queues another. `writePending` is
        // cleared inside the scheduled task (not before the delay), so the whole
        // window coalesces into a single full-file write instead of one per event.
        // Enqueue-only, so this returns to the caller at once.
        if (writePending.compareAndSet(false, true)) {
            runCatching {
                worker.schedule(
                    {
                        writePending.set(false)
                        writeSnapshot()
                    },
                    writeDebounceMs,
                    TimeUnit.MILLISECONDS,
                )
            }.onFailure { writePending.set(false) }
        }
    }

    private fun writeSnapshot() {
        runCatching {
            val text = boundedLogTail(LauncherDebugLog.snapshot(), PERSIST_BUDGET_CHARS)
                .joinToString("\n")
            // Atomic replace: write a temp file, then rename it over the current
            // one. A kill mid-write then leaves the prior *complete* snapshot
            // intact rather than a truncated/empty file — surviving exactly that
            // kill is the point. Fall back to a direct write only if the rename
            // is refused.
            temp.writeText(text)
            if (!temp.renameTo(current)) {
                current.writeText(text)
                temp.delete()
            }
        }
    }

    /**
     * Every unshared prior run's log, oldest first, **one entry per run** — each
     * carrying the id the report hands back to [consumePreviousRuns] and whether
     * that run crashed. Runs on the worker so it is ordered *after* the startup
     * rotation [start] queued there — otherwise a share racing a slow rotation
     * could scan before `debug.log` is renamed and miss the just-ended run (Codex
     * on PR #592). Call off the main thread (this blocks on the worker and reads
     * up to [MAX_PREVIOUS_RUNS] files); a file that fails to read is skipped and
     * left in place, never destroyed.
     *
     * Returning the runs separately rather than one concatenated blob is what
     * lets the report label a crash as a crash, show where one run ends and the
     * next begins, spend its budget on the crashed run first, and — the part that
     * cost a real crash log — delete only the runs it actually carried.
     */
    fun readPreviousRuns(): List<PreviousRunLog> =
        runCatching { worker.submit<List<PreviousRunLog>> { readPreviousRunsOnWorker() }.get() }
            .getOrDefault(emptyList())

    private fun readPreviousRunsOnWorker(): List<PreviousRunLog> =
        previousFiles().mapNotNull { file ->
            // A file that fails to read is skipped, not surfaced and not deleted:
            // the report can only consume ids it was given, so an unreadable run
            // survives for the next attempt. Say so rather than dropping it
            // silently — otherwise a report arrives with no crash section and
            // neither it nor the log explains the hole. No user data in the
            // message: the name is the sink's own rotation stamp.
            val text = runCatching { file.readText() }
                .onFailure { LauncherDebugLog.failure(it, "DebugFileSink could not read a retained prior run; keeping it for the next report") }
                .getOrNull() ?: return@mapNotNull null
            val runLines = text.split("\n").filter { it.isNotEmpty() }
            if (runLines.isEmpty()) {
                null
            } else {
                // This read is itself bounded, and a legacy file written before
                // the per-entry cap can exceed that bound. Say when it did:
                // otherwise the report compares what it rendered against an
                // already-trimmed list, calls the run carried in full, and
                // deletes a file most of which it never saw.
                val bounded = boundedLogTail(runLines, PERSIST_BUDGET_CHARS)
                PreviousRunLog(
                    id = stemOf(file),
                    crashed = isCrashed(file),
                    crashAlreadySeen = file.name.endsWith(PREVIOUS_ACKNOWLEDGED_CRASH_SUFFIX),
                    lines = bounded,
                    truncatedAtRead = bounded != runLines,
                )
            }
        }

    /**
     * Deletes exactly the prior runs named by [ids] — the ones a report actually
     * carried in full. Anything the report trimmed or never read keeps its file,
     * because deleting it would destroy the only copy of a crash the user has
     * just been told they are sharing. Ids come from [readPreviousRuns]; unknown
     * ones are ignored. On the worker, so it can't race the mirror or rotation.
     */
    fun consumePreviousRuns(ids: Collection<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val wanted = ids.toSet()
        return runCatching {
            worker.submit<Set<String>> {
                previousFiles()
                    .filter { stemOf(it) in wanted }
                    // Returning what actually went lets the caller stand a
                    // survivor's prompt down instead of stranding it.
                    .filter { file -> deleteSaying(file, "consume a reported prior run") }
                    .map { stemOf(it) }
                    .toSet()
            }.get()
        }.getOrDefault(emptySet())
    }


    /**
     * Renames the named runs off the crash suffix, keeping their logs. The same
     * rename [acknowledgeCrashBanner] does, aimed at particular runs: a report
     * that carried a crash but could not fit the whole run has still delivered
     * the crash, so the card comes down while the file stays for a later report.
     * Ids not present, or not crash-suffixed, are ignored. On the worker, so it
     * cannot race the rotation or the mirror.
     */
    fun acknowledgeCrashRuns(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val wanted = ids.toSet()
        runCatching {
            worker.submit {
                crashedFiles()
                    .filter { stemOf(it) in wanted }
                    .forEach { file -> acknowledge(file) }
            }.get()
        }
    }

    /**
     * Renames one crashed run to its acknowledged name, saying so when that
     * fails. `renameTo` reports failure by returning false as readily as by
     * throwing, and swallowing either left the card standing after a share the
     * user watched succeed, with nothing anywhere to say why. Leaving the card up
     * is the safe direction — the crash is still offered — but it must not be a
     * silent one. No user data in the message: the name is a rotation stamp.
     */
    private fun acknowledge(file: File) {
        val outcome = runCatching { file.renameTo(acknowledgedNameFor(file)) }
        val thrown = outcome.exceptionOrNull()
        when {
            thrown != null ->
                LauncherDebugLog.failure(thrown, "DebugFileSink could not stand down a reported crash; its prompt stays up")
            outcome.getOrDefault(false) -> Unit
            else ->
                LauncherDebugLog.warning("DebugFileSink could not stand down a reported crash; its prompt stays up")
        }
    }

    /**
     * The same file under [PREVIOUS_ACKNOWLEDGED_CRASH_SUFFIX] — no longer
     * banner-raising, but still recorded as a crash so a later report labels it
     * as one.
     */
    private fun acknowledgedNameFor(file: File): File =
        File(dir, file.name.removeSuffix(PREVIOUS_CRASH_SUFFIX) + PREVIOUS_ACKNOWLEDGED_CRASH_SUFFIX)

    /**
     * Whether a prior run *crashed* (ended in an uncaught exception) and the user
     * has neither shared nor dismissed it — the signal the post-crash banner
     * shows on. A routine kill, force-stop, app update, or silent kill leaves a
     * prior-run file too, but not a crash-suffixed one, so this stays false for
     * them and the banner never mislabels an ordinary exit. Runs on the worker
     * (like [readPreviousRuns]) so FIFO ordering places it after the startup
     * rotation [start] queued there — otherwise a check racing a slow rotation
     * could scan before `debug.log` is renamed to its crash-suffixed name and
     * miss the just-ended crash. Metadata only (a directory listing; never the
     * log itself), so it is cheap; call it off the main thread as it blocks on
     * the worker. Sharing a report consumes the runs it carried
     * ([consumePreviousRuns]) and
     * dismissing renames them off the crash suffix ([acknowledgeCrashBanner]);
     * either way this then returns false until a later run crashes.
     */
    fun hasUnacknowledgedCrash(): Boolean =
        runCatching { worker.submit<Boolean> { crashedFiles().isNotEmpty() }.get() }.getOrElse { false }

    /**
     * Dismiss: rename every crashed prior-run file off the crash suffix so it no
     * longer raises the banner, keeping its log (still shareable in a bug report).
     * Renaming in place — rather than recording a separate ack marker in this
     * evictable cache dir — so cache eviction can't resurrect the prompt by
     * dropping the marker while keeping the crash file. A later crash writes a
     * fresh crash-suffixed file and raises the banner again. On the worker too, so
     * it can't race the rotation or the mirror's reads/writes.
     */
    fun acknowledgeCrashBanner() {
        runCatching {
            worker.submit {
                crashedFiles().forEach { file -> acknowledge(file) }
            }.get()
        }
    }

    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // On the dying thread: record the crash, flush the freshest buffer on
            // the worker (which also holds the rotation, so ordering is preserved),
            // then delegate so Crashlytics captures the fatal report and the
            // process terminates. Wait for the flush, but with a short bounded
            // deadline: the daemon worker's task can be dropped at process death,
            // so a fire-and-forget enqueue could lose the crash snapshot — yet the
            // wait must never delay the chain beyond CRASH_WRITE_TIMEOUT_MS on
            // stalled storage (Codex on PR #592). The atomic temp-then-rename means
            // a write killed at the deadline still leaves the prior complete
            // snapshot intact. (Not installed under Robolectric, so this wait can't
            // stall the test harness's shared dispatcher threads.)
            runCatching {
                // recordUncaught, not warning: Crashlytics' chained handler reports
                // this throwable as a fatal, so warning()'s recordException would
                // double-count it as a non-fatal too (Codex on PR #592). This still
                // buffers + fans out to disk so the crash line is persisted.
                LauncherDebugLog.recordUncaught("Uncaught exception in thread ${thread.name}", throwable)
                // The crash marker rides with the snapshot on the worker (bounded
                // by the same deadline), so the next start rotates this run's log to
                // a banner-raising crash-suffixed name. A graceful run never writes
                // it, so an ordinary process death never raises the banner.
                val flush = worker.submit {
                    runCatching { crashMarker.writeText("1") }
                    writeSnapshot()
                }
                runCatching { flush.get(CRASH_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Test-only: blocks until the worker has drained its queue (incl. rotation). */
    internal fun awaitIdleForTest() {
        worker.submit {}.get()
    }
}

/**
 * The newest lines of [lines] (oldest-first) whose combined length fits
 * [budgetChars], returned oldest-first. Shared by [DebugFileSink] and the bug
 * report so both keep the freshest context inside the ~1 MB Binder limit.
 *
 * A single newest line that alone exceeds the budget is kept **clamped to it**
 * rather than whole: returning nothing would drop the freshest context
 * entirely, but returning it whole would blow the very ceiling this exists to
 * enforce. Live entries are capped at [LOG_BUFFER_MAX_ENTRY_CHARS] so they
 * can't reach that size, but `cacheDir` survives app upgrades — a prior-run
 * file written by a build from before that cap can hold an arbitrarily long
 * line, and it is read back into the report unchanged.
 */
internal fun boundedLogTail(lines: List<String>, budgetChars: Int): List<String> {
    val kept = ArrayDeque<String>()
    var used = 0
    for (line in lines.asReversed()) {
        val cost = line.length + 1 // + the newline appendLine adds
        if (used + cost > budgetChars) {
            if (kept.isNotEmpty()) break
            kept.addFirst(line.take((budgetChars - LOG_TRUNCATION_MARKER.length).coerceAtLeast(0)) + LOG_TRUNCATION_MARKER)
            break
        }
        kept.addFirst(line)
        used += cost
    }
    return kept
}

/** Marks a log line cut short so a reader can tell it was clamped, not written that way. */
private const val LOG_TRUNCATION_MARKER = "…(truncated)"
