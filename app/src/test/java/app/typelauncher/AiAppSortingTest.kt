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
    fun prompt_putsWalletsUnderFinance() {
        // So Google Wallet and its like land in Finance rather than Shopping
        // or Tools (maintainer, 2026-11-19).
        val finance = buildAiSortPrompt(listOf(bank)).lines().first { it.startsWith("finance: ") }

        assertTrue(finance, "wallets" in finance)
    }

    @Test
    fun prompt_hasAHomeFolderForSmartHomeAndProperty() {
        val home = buildAiSortPrompt(listOf(bank)).lines().first { it.startsWith("home: ") }

        assertTrue(home, "smart home" in home && "property" in home)
    }

    @Test
    fun prompt_givesExamples_forTheAppsItGotWrong() {
        // Waze went to tools and Apple TV to other (maintainer, 2026-11-19).
        val prompt = buildAiSortPrompt(listOf(bank))

        assertTrue(prompt, "Waze is travel" in prompt)
        assertTrue(prompt, "Apple TV is media" in prompt)
        assertTrue(prompt, "tools only for a utility no other folder fits" in prompt)
    }

    @Test
    fun promptVersion_followsTheInstructions() {
        // A fingerprint of the folder lines, never the "unset" 0 a fresh
        // install stores, so the first launch after an update re-sorts.
        assertEquals(buildAiSortPrompt(emptyList()).hashCode().let { if (it == 0) 1 else it }, AI_SORT_PROMPT_VERSION)
        assertTrue(AI_SORT_PROMPT_VERSION != 0)
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
