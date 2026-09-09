package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers the bug-report screenshot cache pruning. Each new capture prunes the
 * directory but must keep the most recent previous captures: a FileProvider
 * URI from an earlier share can still be held by its target (an unsent email
 * draft, a lazily-reading messaging app), and the old delete-everything sweep
 * broke that grant retroactively.
 */
class BugReportScreenshotPruneTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun pruneKeepsTheNewestCapturesAndDeletesOlderOnes() {
        val dir = tmp.newFolder("bug-reports")
        val oldest = dir.resolve("screenshot-1000.png").apply { writeBytes(byteArrayOf(1)) }
        val middle = dir.resolve("screenshot-2000.png").apply { writeBytes(byteArrayOf(2)) }
        val newest = dir.resolve("screenshot-3000.png").apply { writeBytes(byteArrayOf(3)) }

        BugReport.prunePersistedScreenshots(dir, keepNewest = 2)

        assertTrue("newest capture must survive", newest.exists())
        assertTrue("second-newest capture must survive", middle.exists())
        assertEquals("only the older capture is pruned", false, oldest.exists())
    }

    @Test
    fun justSharedScreenshotSurvivesTheNextCaptureCycle() {
        // Regression scenario: share bug report A, then trigger bug report B.
        // B's pre-write prune must leave A's file (and therefore A's shared
        // URI) intact instead of deleting every file in the directory.
        val dir = tmp.newFolder("bug-reports")
        val reportA = dir.resolve("screenshot-1000.png").apply { writeBytes(byteArrayOf(1)) }

        BugReport.prunePersistedScreenshots(dir, keepNewest = 2)
        val reportB = dir.resolve("screenshot-2000.png").apply { writeBytes(byteArrayOf(2)) }

        assertTrue("report A's screenshot must outlive report B's capture", reportA.exists())
        assertTrue(reportB.exists())
    }

    @Test
    fun pruneIgnoresUnrelatedFiles() {
        val dir = tmp.newFolder("bug-reports")
        val unrelated = dir.resolve("notes.txt").apply { writeBytes(byteArrayOf(9)) }
        repeat(5) { index ->
            dir.resolve("screenshot-${1000 + index}.png").writeBytes(byteArrayOf(index.toByte()))
        }

        BugReport.prunePersistedScreenshots(dir, keepNewest = 2)

        assertTrue("non-capture files are not the pruner's to delete", unrelated.exists())
        assertEquals(
            "exactly the keepNewest captures remain",
            listOf("screenshot-1003.png", "screenshot-1004.png"),
            dir.listFiles().orEmpty().map { it.name }.filter { it.startsWith("screenshot-") }.sorted(),
        )
    }
}
