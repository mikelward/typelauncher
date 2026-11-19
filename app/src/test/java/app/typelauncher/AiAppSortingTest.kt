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
    fun prompt_hasALearningFolder_andProductivityNoLongerTakesEducation() {
        val lines = buildAiSortPrompt(listOf(bank)).lines()

        assertTrue(lines.first { it.startsWith("learning: ") }.let { "language learning" in it && "e-learning" in it })
        assertTrue(lines.any { "Drops is learning" in it && "Coursera is learning" in it })
        assertTrue(lines.first { it.startsWith("productivity: ") }.let { "education" !in it && "learning" !in it })
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
    fun answer_madeUpFolderOrMissingApp_isLeftOut_forTheCallerToRetry() {
        val answer = "1: banking\n2: games"

        assertEquals(
            mapOf("b" to AppCategory.Games),
            parseAiSortAnswer(answer, listOf(bank, chess, maps)),
        )
    }

    @Test
    fun answer_echoingTheApp_stillReadsTheFolder() {
        // What filed obvious games in Other on a real phone (maintainer,
        // 2026-11-19): the old reader took "The" as the folder.
        val walkingDead = AiSortInput("w", "The Walking Dead", "com.skybound.twd")
        val tv = AiSortInput("t", "Apple TV", "com.apple.atve.androidtv.appletv")
        val answer = """
            1. The Walking Dead (com.skybound.twd): games
            2. Apple TV (com.apple.atve.androidtv.appletv) - Media
        """.trimIndent()

        assertEquals(
            mapOf("w" to AppCategory.Games, "t" to AppCategory.Media),
            parseAiSortAnswer(answer, listOf(walkingDead, tv)),
        )
    }

    @Test
    fun answer_appNamesThatAreFolderNames_arentReadAsTheAnswer() {
        // Codex on PR #857: the folder before an echoed name, and an echo
        // with no answer at all.
        val bbc = AiSortInput("b", "BBC News", "bbc.mobile.news.uk")
        val home = AiSortInput("h", "Google Home", "com.google.android.apps.chromecast.app")
        val shop = AiSortInput("s", "Home Depot", "com.thehomedepot")
        val answer = """
            1: media (BBC News)
            2: tools (Google Home)
            3. Home Depot
        """.trimIndent()

        assertEquals(
            mapOf("b" to AppCategory.Media, "h" to AppCategory.Tools),
            parseAiSortAnswer(answer, listOf(bbc, home, shop)),
        )
    }

    @Test
    fun answer_forAnAppNamedLikeAFolder_isStillRead() {
        // Codex on PR #857: an app called News answered "news".
        val news = AiSortInput("n", "News", "com.example.news")
        val home = AiSortInput("h", "Home", "com.example.home")
        val games = AiSortInput("g", "Games", "com.example.games")
        val answer = "1: news\n2. Home: tools\n3. Games (com.example.games): games"

        assertEquals(
            mapOf("n" to AppCategory.News, "h" to AppCategory.Tools, "g" to AppCategory.Games),
            parseAiSortAnswer(answer, listOf(news, home, games)),
        )
    }

    @Test
    fun answer_anAppNamedLikePartOfAFolder_isntCutOutOfTheAnswer() {
        // Codex on PR #857: an app called Learn answered "learning" left "ing".
        val learn = AiSortInput("l", "Learn", "com.example.learn")
        val game = AiSortInput("g", "Gam", "com.example.gam")
        val answer = "1: learning\nGam: games"

        assertEquals(
            mapOf("l" to AppCategory.Learning, "g" to AppCategory.Games),
            parseAiSortAnswer(answer, listOf(learn, game)),
        )
    }

    @Test
    fun answer_withoutNumbers_matchesByName_andReadsSingularFolders() {
        val home = AiSortInput("h", "Google Home", "com.google.android.apps.chromecast.app")
        val homeless = AiSortInput("x", "Home", "com.example.home")
        val answer = "Chess: game\nGoogle Home: home\nHome - tool"

        assertEquals(
            mapOf("b" to AppCategory.Games, "h" to AppCategory.Home, "x" to AppCategory.Tools),
            parseAiSortAnswer(answer, listOf(bank, chess, home, homeless)),
        )
    }

    @Test
    fun answer_withoutNumbers_aFolderWordNamingAnotherApp_doesntStealTheLine() {
        // Codex on PR #857: "CNN: news" was matched to the app called News,
        // the longer name, and News's own answer was lost.
        val cnn = AiSortInput("c", "CNN", "com.cnn.mobile.android.phone")
        val news = AiSortInput("n", "News", "com.example.news")
        val answer = "CNN: news\nNews: media"

        assertEquals(
            mapOf("c" to AppCategory.News, "n" to AppCategory.Media),
            parseAiSortAnswer(answer, listOf(cnn, news)),
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
