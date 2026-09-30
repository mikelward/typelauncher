package app.typelauncher

import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.android.DebugFileSink
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A report consumes only the earlier runs its text carries. It used to read them
 * whole and trim the text when rendering, so the handle it cleared still named
 * every run the trim had cut — and sharing the report deleted those unsent.
 */
class BugReportPreviousRunTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aRunTheReportLeftOutSurvivesTheShare() {
        val dir = folder.newFolder()
        val older = File(dir, "androidlog-prev-1.log").apply { writeText("the oldest run\n") }
        val newer = File(dir, "androidlog-prev-2.log").apply {
            writeText((0 until 1_000).joinToString("\n") { "line-$it of a talkative run" } + "\n")
        }
        assertTrue(older.setLastModified(1_000L))
        assertTrue(newer.setLastModified(2_000L))
        val sink = DebugFileSink(DebugLog(), dir)
        // The premise: an unbounded read would have carried the older run, so it
        // is the report's own share that leaves it out.
        assertTrue(sink.readPreviousRun()!!.text.contains("the oldest run"))

        val run = readPreviousRunForReport(sink)!!
        assertFalse(run.text, run.text.contains("the oldest run"))
        assertTrue("the newest lines are kept", run.text.endsWith("line-999 of a talkative run"))
        sink.clearPreviousRun(run)
        sink.awaitIdle()

        assertTrue("the run nobody was sent survives", older.exists())
        assertFalse("the run that was sent does not", newer.exists())
    }
}
