package app.typelauncher

import com.mikelward.androidlog.safe
import java.io.File

/**
 * The names Type Launcher's own file sink used before the shared library's sink
 * replaced it.
 *
 * The library hard-codes `androidlog.log` / `androidlog-prev-*`, so the swap
 * would otherwise leave every prior run already on a device invisible: the new
 * sink never lists them, never reads them into a report, and never deletes
 * them. An unshared crash log among them would be lost with nothing to say so,
 * which is the failure the carry-over below exists to prevent — not tidiness.
 */
private const val LEGACY_CURRENT_FILE = "debug.log"
private const val LEGACY_PREVIOUS_PREFIX = "debug-prev-"
private const val LEGACY_CRASH_MARKER_FILE = "$LEGACY_CURRENT_FILE.crash"
private const val LEGACY_TEMP_FILE = "$LEGACY_CURRENT_FILE.tmp"

private const val PREVIOUS_PREFIX = "androidlog-prev-"
private const val PLAIN_SUFFIX = ".log"
private const val CRASH_SUFFIX = ".crash.log"

/**
 * The name the run that was in progress when the old sink last wrote is carried
 * over under.
 *
 * A fixed name rather than a timestamp, because **the library orders prior runs
 * by modification time, not by name** — it reads each file's time through
 * `java.nio.file` and sorts on that. `renameTo` preserves that time, so the
 * carried-over run keeps its true position with no timestamp to invent. It only
 * has to be unique, and it is: nothing else in the directory can hold it, and
 * the carry-over refuses to overwrite.
 */
private const val CARRIED_CURRENT_RUN_NAME = "androidlog-prev-legacy"

/**
 * Carries logs written by the old sink onto the names the shared library's sink
 * reads. Returns what it did, for the log line.
 *
 * **Never call this on the main thread** — it lists a directory and renames
 * files. [TypeLauncherApp] runs it on a background thread during `onCreate`.
 *
 * Deliberately narrow, and deliberately not a "migration framework":
 *
 * - **The run that was in progress is carried over too**, not dropped. The
 *   process that wrote it has already ended, so its content belongs to a prior
 *   run — and it is the *newest* one, which makes it the likeliest thing a user
 *   opening the app after a crash is about to report. It keeps its crash
 *   classification: the legacy crash marker decides the suffix, so the
 *   post-crash card still raises for it.
 * - **The legacy crash marker is consumed, never renamed.** The library reads
 *   its own marker to mean *this* run crashed, so carrying it across would make
 *   a fresh start believe it had just crashed. It is read for the suffix above
 *   and then deleted.
 * - **Never overwrites.** A destination that already exists means the new sink
 *   has been running for a while and this is a stale leftover; the legacy file
 *   is dropped rather than allowed to clobber a real run.
 * - **Runs every start, not once.** There is no "migrated" flag to get out of
 *   step with the disk. After the first pass there is nothing to list, so the
 *   cost is one directory listing on a background thread.
 *
 * Failures are counted, not thrown: a cache directory that cannot be listed or
 * a rename the filesystem refuses must not take down `onCreate`, and the caller
 * logs the outcome either way.
 */
internal fun carryOverLegacyDebugLogs(dir: File): LegacyCarryOver {
    val listed = dir.listFiles() ?: return LegacyCarryOver(carried = 0, failed = 0, listed = false)
    val tally = CarryOverTally()

    // Phase 1: the run that was in progress, and the marker that classifies it.
    //
    // Ordered against each other on purpose. The marker only means anything
    // while its log is still here, so consuming it independently could lose the
    // classification of a log that stayed behind: a failed rename plus a
    // successful delete leaves the next launch carrying the run as *plain*, and
    // the crash card never appears for a crash that really happened (Codex,
    // PR #708). So the marker is read first, and removed only once its log no
    // longer needs it.
    val endedInCrash = listed.any { it.name == LEGACY_CRASH_MARKER_FILE }
    val currentRun = listed.firstOrNull { it.name == LEGACY_CURRENT_FILE }
    val currentRunSettled = if (currentRun == null) {
        true
    } else {
        val suffix = if (endedInCrash) CRASH_SUFFIX else PLAIN_SUFFIX
        tally.move(currentRun, File(dir, CARRIED_CURRENT_RUN_NAME + suffix))
    }
    for (file in listed) {
        when {
            // Consumed, not carried: the library reads its own marker to mean
            // *this* run crashed, so carrying it across would tell a freshly
            // started process it had just crashed. Held back while its log
            // still needs it, per the phase note above.
            file.name == LEGACY_CRASH_MARKER_FILE -> if (currentRunSettled) tally.drop(file)
            // A half-written mirror carries nothing the finished file does not.
            file.name == LEGACY_TEMP_FILE -> tally.drop(file)
        }
    }

    // Phase 2: runs the old sink had already rotated aside.
    for (file in listed) {
        if (!file.name.startsWith(LEGACY_PREVIOUS_PREFIX)) continue
        tally.move(file, File(dir, PREVIOUS_PREFIX + file.name.removePrefix(LEGACY_PREVIOUS_PREFIX)))
    }

    return LegacyCarryOver(carried = tally.carried, failed = tally.failed, listed = true)
}

/**
 * Counts the three outcomes apart — carried over, deliberately dropped, failed —
 * and logs every failure with the operation that failed.
 *
 * Collapsing "carried" and "dropped" would report a stale leftover we merely
 * deleted as a run rescued. Swallowing the throwable would leave an incomplete
 * migration undiagnosable, which the repository's error-handling rule forbids
 * and which matters here: what fails is the last copy of somebody's crash log.
 *
 * The names logged are this app's own cache-file names, fixed at compile time —
 * no path, and nothing of the user's.
 */
private class CarryOverTally {
    var carried = 0
        private set
    var failed = 0
        private set

    /**
     * Renames [file] to [destination], or deletes it when the destination is
     * already a run — which means the new sink has been running and this legacy
     * file is the stale one, never something to clobber a real run with.
     *
     * `isFile`, not `exists`: anything at that path which is *not* a file is not
     * a run, and dropping a legacy log on the strength of it would destroy the
     * only copy to make way for something that never was one. The rename is
     * attempted instead, and its refusal counted like any other.
     *
     * Returns whether the file is dealt with, so a caller can hold back
     * something that depends on it.
     */
    fun move(file: File, destination: File): Boolean {
        if (destination.isFile) return drop(file)
        val renamed = attempt("renaming ${file.name} to ${destination.name}") {
            file.renameTo(destination)
        }
        if (renamed) carried++
        return renamed
    }

    /** Deletes [file], which carries nothing a prior run needs. */
    fun drop(file: File): Boolean = attempt("deleting ${file.name}") { file.delete() }

    /**
     * Runs one filesystem [operation], counting and logging a failure exactly
     * once however it fails.
     *
     * Both failure modes matter and they are not interchangeable. `renameTo`
     * and `delete` **return false** when the filesystem simply refuses, which is
     * the likelier case; they **throw** a `SecurityException` when a manager
     * denies access. Swallowing either would leave an incomplete migration with
     * only an aggregate count and no operation named, which the repository's
     * error-handling rule forbids and which matters here: what failed is the
     * last copy of somebody's crash log.
     *
     * [what] names this app's own cache-file names, fixed at compile time — no
     * path, and nothing of the user's.
     */
    private inline fun attempt(what: String, operation: () -> Boolean): Boolean {
        val succeeded = try {
            operation()
        } catch (e: SecurityException) {
            failed++
            LauncherDebugLog.failure(e, "debug log carry-over refused: %s", safe(what))
            return false
        }
        if (!succeeded) {
            failed++
            LauncherDebugLog.event("debug log carry-over failed: %s", safe(what))
        }
        return succeeded
    }
}

/** What [carryOverLegacyDebugLogs] did, so the caller can log it without re-reading the disk. */
internal data class LegacyCarryOver(
    val carried: Int,
    val failed: Int,
    /** False when the directory could not be listed at all — distinct from "nothing to do". */
    val listed: Boolean,
) {
    val didSomething: Boolean get() = carried > 0 || failed > 0 || !listed
}
