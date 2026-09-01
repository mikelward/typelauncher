package app.typelauncher

import com.mikelward.androidlog.DebugLog

/**
 * Puts a [DebugLog] back to a known state between tests.
 *
 * Replaces the `clearForTest()` / `clearSinksForTest()` pair the app's own
 * logger carried before it became
 * [mikelward/androidlog](https://github.com/mikelward/androidlog). The library
 * deliberately has no such seam — it takes its clock as a constructor argument
 * so a test can build a fresh instance instead — but `LauncherDebugLog` is a
 * process-wide `object` these tests share, so it still needs resetting.
 *
 * **The order is the point.** Sinks come off first: disabling fans
 * `Sink.onCleared()` out to everything still attached, and a [DebugFileSink]
 * answers that by purging its file — which is correct in production and would
 * silently destroy the fixture a file-sink test had just set up. Detached
 * first, the disable reaches nobody.
 *
 * The disable/enable pair is what empties the buffer; the library clears it on
 * the way down and there is no other way in, which is itself deliberate ("off"
 * has to mean off, and a seam that emptied the buffer without going through
 * the gate would be a second path to a state the gate is supposed to own).
 */
internal fun DebugLog.resetForTest() {
    clearSinks()
    setRecording(false)
    setRecording(true)
}

/**
 * [DebugLog.snapshot] without the offset-anchor markers.
 *
 * The log stamps no offset on an ordinary line; it writes one marker line
 * naming the offset instead, and again whenever the offset changes, so a log
 * read days later in another zone is still reconstructible. That marker is a
 * real entry, so a test counting or indexing entries has to say whether it
 * means them.
 */
internal fun DebugLog.entriesForTest(): List<String> =
    snapshot().filterNot { it.contains(" timezone offset ") }
