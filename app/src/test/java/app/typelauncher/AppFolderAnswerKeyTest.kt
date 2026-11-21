package app.typelauncher

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scores the name and package rules ([knownAppCategory], then
 * [guessedAppCategory]) against an answer key of top Play apps, each with
 * the folder a person would file it in (maintainer, 2026-11-19). An app the
 * rules leave alone is left to its manifest or the AI, so it isn't wrong;
 * an app they put in the wrong folder is. The floors only rise: when a
 * change scores better, raise them.
 */
class AppFolderAnswerKeyTest {
    private data class Row(val packageName: String, val name: String, val folder: String)

    private val rows: List<Row> = javaClass.classLoader!!
        .getResourceAsStream("app-folder-answer-key.tsv")!!
        .bufferedReader()
        .readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val (packageName, name, folder) = line.split("\t")
            Row(packageName, name, folder)
        }

    @Test
    fun theRules_fileTopAppsCorrectly() {
        var right = 0
        val wrong = mutableListOf<String>()
        for (row in rows) {
            val got = (knownAppCategory(row.packageName, row.name) ?: guessedAppCategory(row.packageName, row.name))
                ?.storageKey ?: continue
            if (got == row.folder) right++ else wrong += "${row.name}: ${row.folder}, not $got"
        }
        val report = "right $right, wrong ${wrong.size} of ${rows.size}:\n" + wrong.joinToString("\n")
        println(report)
        assertTrue(report, rows.size >= 300)
        assertTrue(report, right >= MIN_RIGHT)
        assertTrue(report, wrong.size <= MAX_WRONG)
    }

    private companion object {
        const val MIN_RIGHT = 274
        const val MAX_WRONG = 23
    }
}
