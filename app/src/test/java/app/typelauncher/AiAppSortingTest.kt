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

        assertTrue(prompt, "Waze is transport" in prompt)
        assertTrue(prompt, "Apple TV is media" in prompt)
        assertTrue(prompt, "tools only for a utility no other folder fits" in prompt)
    }

    @Test
    fun promptVersion_followsTheInstructions() {
        // A fingerprint of the folder lines, never the "unset" 0 a fresh
        // install stores, so the first launch after an update re-sorts.
        assertEquals(buildAiSortPrompt(emptyList()).hashCode().let { if (it == 0) 1 else it }, AI_SORT_PROMPT_VERSION)
        // A folder added or deleted changes it too.
        val withKids = AiSortFolders(activeAppFolders(listOf(AppFolderDef("custom-1", null, "Toys", FolderIcon.ChildCare)), emptySet()))
        assertTrue(withKids.version != AI_SORT_PROMPT_VERSION)
        assertTrue(AiSortFolders(activeAppFolders(emptyList(), setOf("games"))).version != AI_SORT_PROMPT_VERSION)
        assertTrue(AI_SORT_PROMPT_VERSION != 0)
    }

    @Test
    fun prompt_listsOnlyTheFoldersInUse_withTheUsersOwnByName() {
        // The maintainer, 2026-11-19: sort into the active folders, the
        // user's own included.
        val folders = AiSortFolders(
            activeAppFolders(listOf(AppFolderDef("custom-1", null, "Kids Stuff", FolderIcon.ChildCare)), setOf("games", "tools")),
        )

        val lines = buildAiSortPrompt(listOf(bank), folders).lines()

        assertTrue(lines.any { it.startsWith("kids stuff: ") })
        assertFalse(lines.any { it.startsWith("games: ") || it.startsWith("tools: ") })
        // Examples for a deleted folder would steer the model into it.
        assertFalse(lines.any { "is games" in it || "is tools" in it })
        assertTrue(lines.any { it == "Choose other only when nothing fits." })
    }

    @Test
    fun prompt_hasTheNewBuiltIns_andMediaNoLongerTakesMusicOrPhotos() {
        val lines = buildAiSortPrompt(listOf(bank)).lines()

        for (word in listOf("ai", "code", "food", "government", "internet", "kids", "music", "pets", "phone", "photos", "reading", "sports", "transport", "weather")) {
            assertTrue(word, lines.any { it.startsWith("$word: ") })
        }
        val media = lines.first { it.startsWith("media: ") }
        assertFalse(media, "music" in media || "photo" in media)
        assertTrue(lines.any { "Red Dead Redemption is games" in it })
        assertTrue(lines.first { it.startsWith("government: ") }.contains("driver's licenses"))
    }

    @Test
    fun answer_readsTheUsersFolderNames_andSkipsDeletedOnes() {
        val folders = AiSortFolders(
            activeAppFolders(listOf(AppFolderDef("custom-1", null, "Kids Stuff", FolderIcon.ChildCare)), setOf("games")),
        )
        val answer = "1: Kids Stuff\n2: games\n3: photo"

        assertEquals(
            mapOf("a" to "custom-1", "c" to "photos"),
            parseAiSortAnswer(answer, listOf(bank, chess, maps), folders),
        )
    }

    @Test
    fun answer_aBuiltInsWord_staysTheBuiltIns_whateverAUserFolderIsCalled() {
        // Codex on PR #860: "(Games)" reads as "games" and sorts first.
        val folders = AiSortFolders(
            activeAppFolders(listOf(AppFolderDef("custom-1", null, "(Games)", FolderIcon.Star)), emptySet()),
        )

        assertEquals(mapOf("a" to "games"), parseAiSortAnswer("1: games", listOf(bank), folders))
    }

    @Test
    fun answer_aUserFolderNamedAfterTheApp_isntReadAsAnEcho() {
        // Codex on PR #860: an app called Home, a folder called Home Stuff.
        val home = AiSortInput("h", "Home", "com.example.home")
        val folders = AiSortFolders(
            activeAppFolders(listOf(AppFolderDef("custom-1", null, "Home Stuff", FolderIcon.Home)), emptySet()),
        )

        assertEquals(mapOf("h" to "custom-1"), parseAiSortAnswer("1: home stuff", listOf(home), folders))
        assertEquals(mapOf("h" to "tools"), parseAiSortAnswer("1. Home: tools", listOf(home), folders))
    }

    @Test
    fun answer_readsAUserFolderNamedWithPunctuation() {
        // Codex on PR #860: "Work (personal)", and a name led by an emoji.
        val folders = AiSortFolders(
            activeAppFolders(
                listOf(
                    AppFolderDef("custom-1", null, "Work (personal)", FolderIcon.Work),
                    AppFolderDef("custom-2", null, "\u2B50 Faves", FolderIcon.Star),
                ),
                emptySet(),
            ),
        )
        val prompt = buildAiSortPrompt(listOf(bank), folders)
        val answer = "1: work (personal)\n2: Work personal\n3: \u2B50 faves"

        assertTrue(prompt, prompt.lines().any { it.startsWith("work personal: ") })
        assertEquals(
            mapOf("a" to "custom-1", "b" to "custom-1", "c" to "custom-2"),
            parseAiSortAnswer(answer, listOf(bank, chess, maps), folders),
        )
    }

    @Test
    fun prompt_listsTheUsersFoldersFirst_andAsksForThemFirst() {
        val folders = AiSortFolders(
            activeAppFolders(listOf(AppFolderDef("custom-1", null, "Coding", FolderIcon.Code)), emptySet()),
        )

        val lines = buildAiSortPrompt(listOf(bank), folders).lines()

        assertEquals("coding", lines[1].substringBefore(":"))
        assertTrue(lines[1], "rather than" in lines[1])
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
            mapOf("a" to "finance", "b" to "games", "c" to "travel"),
            parseAiSortAnswer(answer, listOf(bank, chess, maps)),
        )
    }

    @Test
    fun answer_madeUpFolderOrMissingApp_isLeftOut_forTheCallerToRetry() {
        val answer = "1: banking\n2: games"

        assertEquals(
            mapOf("b" to "games"),
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
            mapOf("w" to "games", "t" to "media"),
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
            mapOf("b" to "media", "h" to "tools"),
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
            mapOf("n" to "news", "h" to "tools", "g" to "games"),
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
            mapOf("l" to "learning", "g" to "games"),
            parseAiSortAnswer(answer, listOf(learn, game)),
        )
    }

    @Test
    fun answer_withoutNumbers_matchesByName_andReadsSingularFolders() {
        val home = AiSortInput("h", "Google Home", "com.google.android.apps.chromecast.app")
        val homeless = AiSortInput("x", "Home", "com.example.home")
        val answer = "Chess: game\nGoogle Home: home\nHome - tool"

        assertEquals(
            mapOf("b" to "games", "h" to "home", "x" to "tools"),
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
            mapOf("c" to "news", "n" to "media"),
            parseAiSortAnswer(answer, listOf(cnn, news)),
        )
    }

    @Test
    fun answer_firstLineForANumberWins_andStrayNumbersAreIgnored() {
        val answer = "1: finance\n1: games\n7: social\nSure! Here you go."

        assertEquals(
            mapOf("a" to "finance"),
            parseAiSortAnswer(answer, listOf(bank)),
        )
    }
}
