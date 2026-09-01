package app.typelauncher

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The one-time carry-over from the launcher's former file sink to the shared
 * library's.
 *
 * The library hard-codes its file names, so without this a device that upgrades
 * keeps its prior runs on disk where nothing will ever read or delete them —
 * including an unshared crash log, lost silently. These tests pin that the
 * carry-over moves what it should, leaves what it should, and never destroys a
 * run the new sink already owns.
 */
class LegacyDebugLogFilesTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `prior runs are carried over under the names the new sink reads`() {
        val dir = folder.newFolder()
        File(dir, "debug-prev-123.log").writeText("an ordinary earlier run\n")
        File(dir, "debug-prev-456.crash.log").writeText("an earlier run that crashed\n")

        val outcome = carryOverLegacyDebugLogs(dir)

        assertEquals("both prior runs are carried over", 2, outcome.carried)
        assertEquals("and none failed", 0, outcome.failed)
        assertTrue(outcome.listed)
        assertEquals(
            "the crash suffix survives, so the banner still recognizes it",
            "an earlier run that crashed\n",
            File(dir, "androidlog-prev-456.crash.log").readText(),
        )
        assertEquals(
            "an ordinary earlier run\n",
            File(dir, "androidlog-prev-123.log").readText(),
        )
        assertFalse(File(dir, "debug-prev-123.log").exists())
        assertFalse(File(dir, "debug-prev-456.crash.log").exists())
    }

    @Test
    fun `a run the new sink already owns is never overwritten`() {
        val dir = folder.newFolder()
        File(dir, "debug-prev-123.log").writeText("the legacy leftover\n")
        File(dir, "androidlog-prev-123.log").writeText("the run the new sink owns\n")

        val outcome = carryOverLegacyDebugLogs(dir)

        assertEquals(
            "the run the new sink owns\n",
            File(dir, "androidlog-prev-123.log").readText(),
        )
        assertFalse("and the stale leftover is not left to accumulate", File(dir, "debug-prev-123.log").exists())
        assertEquals("a dropped leftover is not counted as carried over", 0, outcome.carried)
    }

    @Test
    fun `the run in progress is carried over, keeping its crash classification`() {
        val dir = folder.newFolder()
        File(dir, "debug.log").writeText("the run that just crashed\n")
        File(dir, "debug.log.crash").writeText("")
        File(dir, "debug.log.tmp").writeText("a half-written mirror\n")

        val outcome = carryOverLegacyDebugLogs(dir)

        // The newest run is the likeliest thing a user opening the app after a
        // crash is about to report, so losing it is the worst outcome here.
        assertEquals(
            "the run that just crashed\n",
            File(dir, "androidlog-prev-legacy.crash.log").readText(),
        )
        assertEquals(1, outcome.carried)
        // Carrying the marker across would tell the new sink that *this* run
        // crashed, so it is read for the suffix above and then consumed.
        assertFalse(File(dir, "androidlog.log.crash").exists())
        assertFalse(File(dir, "debug.log.crash").exists())
        assertFalse("nothing is promoted to the new current-run name", File(dir, "androidlog.log").exists())
        assertFalse(File(dir, "debug.log").exists())
        assertFalse("the half-written mirror carries nothing the file does not", File(dir, "debug.log.tmp").exists())
    }

    @Test
    fun `a run in progress that did not crash is carried over unsuffixed`() {
        val dir = folder.newFolder()
        File(dir, "debug.log").writeText("an ordinary run that was killed\n")

        carryOverLegacyDebugLogs(dir)

        assertEquals(
            "an ordinary run that was killed\n",
            File(dir, "androidlog-prev-legacy.log").readText(),
        )
        assertFalse(
            "without a marker it must not be filed as a crash",
            File(dir, "androidlog-prev-legacy.crash.log").exists(),
        )
    }

    @Test
    fun `the crash marker survives a carry-over its log did not`() {
        val dir = folder.newFolder()
        File(dir, "debug.log").writeText("the run that just crashed\n")
        File(dir, "debug.log.crash").writeText("")
        // A non-empty directory in the destination's place: the rename is
        // refused while everything around it still works, which is the shape of
        // a filesystem simply saying no. Deleting the marker would have
        // succeeded, so this isolates the ordering rather than a dead sandbox.
        File(dir, "androidlog-prev-legacy.crash.log").mkdir()
        File(dir, "androidlog-prev-legacy.crash.log/occupied").writeText("x")

        val outcome = carryOverLegacyDebugLogs(dir)

        assertEquals("the refusal is counted", 1, outcome.failed)
        assertTrue("the log stays for the next attempt", File(dir, "debug.log").exists())
        // The marker is the only thing that says this run crashed. Consuming it
        // while its log is still here would have the next launch carry that log
        // as an ordinary run, so the crash card never appears for a crash that
        // really happened.
        assertTrue(
            "and so does the marker that classifies it",
            File(dir, "debug.log.crash").exists(),
        )
    }

    @Test
    fun `the marker goes once its log no longer needs it`() {
        val dir = folder.newFolder()
        File(dir, "debug.log").writeText("the run that just crashed\n")
        File(dir, "debug.log.crash").writeText("")

        carryOverLegacyDebugLogs(dir)

        assertTrue(File(dir, "androidlog-prev-legacy.crash.log").exists())
        assertFalse(
            "carried over, so nothing still depends on the marker",
            File(dir, "debug.log.crash").exists(),
        )
    }

    @Test
    fun `unrelated files in the cache directory are left alone`() {
        val dir = folder.newFolder()
        File(dir, "some-other-cache-entry").writeText("not ours\n")
        File(dir, "androidlog-prev-1.log").writeText("already migrated\n")

        val outcome = carryOverLegacyDebugLogs(dir)

        assertEquals(0, outcome.carried)
        assertEquals(0, outcome.failed)
        assertFalse("a start with nothing to do is silent", outcome.didSomething)
        assertTrue(File(dir, "some-other-cache-entry").exists())
        assertTrue(File(dir, "androidlog-prev-1.log").exists())
    }

    @Test
    fun `a directory that cannot be listed is reported rather than read as empty`() {
        // A regular file is not a directory, so listFiles() returns null — the
        // same signal a permission failure gives. "Could not look" must not be
        // recorded as "nothing to carry over"; the caller logs it.
        val notADirectory = folder.newFile()

        val outcome = carryOverLegacyDebugLogs(notADirectory)

        assertFalse(outcome.listed)
        assertTrue("so the caller says something rather than staying silent", outcome.didSomething)
    }
}
