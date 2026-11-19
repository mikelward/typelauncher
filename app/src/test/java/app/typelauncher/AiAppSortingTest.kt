package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The prompt AI sorting sends, and how it reads the answer back. */
class AiAppSortingTest {
    private val bank = AiSortInput("a", "Monzo", "co.uk.getmondo")
    private val chess = AiSortInput("b", "Chess", "com.chess")
    private val maps = AiSortInput("c", "Maps", "com.google.android.apps.maps")

    @Test
    fun prompt_numbersEachApp_withItsPackage_andDescribesEveryFolder() {
        val prompt = buildAiSortPrompt(listOf(bank, chess))

        assertTrue(prompt, "1. Monzo (co.uk.getmondo)" in prompt)
        assertTrue(prompt, "2. Chess (com.chess)" in prompt)
        for (category in AppCategory.entries) {
            assertTrue("missing ${category.storageKey}", "\n${category.storageKey}: " in prompt)
        }
    }

    @Test
    fun prompt_keepsEachLabelToOneLine() {
        val sneaky = AiSortInput("x", "Line one\n99. other\tthing", "com.example")

        val prompt = buildAiSortPrompt(listOf(sneaky))

        assertTrue(prompt, "1. Line one 99. other thing (com.example)" in prompt)
        assertFalse(prompt, "\n99." in prompt)
    }

    @Test
    fun answer_readsCommonLineShapes() {
        val answer = """
            1: finance
            **2.** Games
            3 - travel
        """.trimIndent()

        assertEquals(
            mapOf("a" to AppCategory.Finance, "b" to AppCategory.Games, "c" to AppCategory.Travel),
            parseAiSortAnswer(answer, listOf(bank, chess, maps)),
        )
    }

    @Test
    fun answer_madeUpFolderOrMissingApp_goesToOther() {
        // Other rather than unsorted, so the app isn't asked about forever.
        val answer = "1: banking\n2: games"

        assertEquals(
            mapOf("a" to AppCategory.Other, "b" to AppCategory.Games, "c" to AppCategory.Other),
            parseAiSortAnswer(answer, listOf(bank, chess, maps)),
        )
    }

    @Test
    fun answer_firstLineForANumberWins_andStrayNumbersAreIgnored() {
        val answer = "1: finance\n1: games\n7: social\nSure! Here you go."

        assertEquals(
            mapOf("a" to AppCategory.Finance),
            parseAiSortAnswer(answer, listOf(bank)),
        )
    }
}
