package app.typelauncher

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Immutable
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/**
 * "Sort with on-device AI": files the apps whose manifest names no category
 * into app-list folders, by asking an on-device model about each app's label
 * and package name. Nothing about the apps leaves the device.
 */

/** What AI sorting is doing, for Settings and the Other folder. */
internal enum class AiSortPhase { Idle, Unavailable, Downloading, Sorting, Done, Paused }

/** [sorted] of [total] apps the AI files have been sorted. */
@Immutable
internal data class AiSortProgress(
    val phase: AiSortPhase = AiSortPhase.Idle,
    val sorted: Int = 0,
    val total: Int = 0,
)

/** Where the on-device model stands, as AICore reports it. */
internal enum class AiModelStatus { Unavailable, Downloadable, Downloading, Available }

/**
 * Why a model call failed, in the terms the sorter acts on. [Busy] is a short
 * burst limit worth retrying after a wait; [Background] means the launcher
 * isn't the top app, so sorting waits for Home to return; [QuotaExceeded] is
 * the long (daily) limit; [Failed] is anything else.
 */
internal sealed class AiModelException(message: String, cause: Throwable?) : Exception(message, cause) {
    class Busy(val retryAfterMillis: Long?, cause: Throwable? = null) : AiModelException("busy", cause)
    class Background(cause: Throwable? = null) : AiModelException("background use blocked", cause)
    class QuotaExceeded(cause: Throwable? = null) : AiModelException("quota exceeded", cause)
    class Failed(message: String, cause: Throwable? = null) : AiModelException(message, cause)
}

/** The model the sorter asks. An interface so tests can stand in for AICore. */
internal interface AppCategoryModel {
    suspend fun status(): AiModelStatus

    /** Downloads the model, emitting bytes so far; completes when it's ready. */
    fun download(): Flow<Long>

    /** The model's answer to [prompt]. Throws [AiModelException]. */
    suspend fun generate(prompt: String): String
}

/** Gemini Nano through ML Kit's Prompt API, which runs it in AICore. */
internal class GeminiNanoCategoryModel : AppCategoryModel {
    // Created on first use, so ML Kit does nothing until the user opts in.
    private val client: GenerativeModel by lazy { Generation.getClient() }

    override suspend fun status(): AiModelStatus = try {
        when (client.checkStatus()) {
            FeatureStatus.AVAILABLE -> AiModelStatus.Available
            FeatureStatus.DOWNLOADABLE -> AiModelStatus.Downloadable
            FeatureStatus.DOWNLOADING -> AiModelStatus.Downloading
            else -> AiModelStatus.Unavailable
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: GenAiException) {
        throw exception.toAiModelException()
    }

    override fun download(): Flow<Long> = flow {
        client.download().collect { status ->
            when (status) {
                is DownloadStatus.DownloadProgress -> emit(status.totalBytesDownloaded)
                is DownloadStatus.DownloadFailed -> throw status.e.toAiModelException()
                else -> Unit // Started, and Completed, which ends the flow.
            }
        }
    }

    override suspend fun generate(prompt: String): String = try {
        val response = client.generateContent(
            generateContentRequest(TextPart(prompt)) {
                // The same apps should land in the same folders every time.
                temperature = 0f
                topK = 1
                maxOutputTokens = AI_SORT_MAX_OUTPUT_TOKENS
            },
        )
        response.candidates.firstOrNull()?.text.orEmpty()
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: GenAiException) {
        throw exception.toAiModelException()
    }
}

private fun GenAiException.toAiModelException(): AiModelException = when (errorCode) {
    GenAiException.ErrorCode.BUSY -> AiModelException.Busy(retryDelay?.toMillis(), this)
    GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> AiModelException.Background(this)
    GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> AiModelException.QuotaExceeded(this)
    else -> AiModelException.Failed("GenAiException code=$errorCode", this)
}

/**
 * Whether this device has AICore, the system service that runs Gemini Nano.
 * A package lookup rather than an ML Kit call, so Settings can decide whether
 * to offer AI sorting without starting ML Kit before the user opts in. AICore
 * can be present on a device whose model still isn't supported; the sorter
 * finds that out when it starts. Does a binder call: off the main thread.
 */
internal fun isAiCorePresent(context: Context): Boolean = try {
    context.packageManager.getPackageInfo(AICORE_PACKAGE, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    // The expected answer on most devices, not an error worth a log line.
    false
} catch (exception: RuntimeException) {
    LauncherDebugLog.failure(exception, "isAiCorePresent lookup failed")
    false
}

private const val AICORE_PACKAGE = "com.google.android.aicore"

/** What the model is told about one app. */
internal data class AiSortInput(val id: String, val label: String, val packageName: String)

/**
 * The folders AI sorting files into: the ones in use, built-in and the
 * user's own, each under the word the model answers with. A built-in is its
 * storage key ("games"); a folder the user made is its name, lowercased
 * ("kids stuff"). Where a name repeats a built-in's word, the built-in keeps
 * it.
 */
internal class AiSortFolders(val folders: List<AppFolderDef>) {
    private val keyByWord: Map<String, String>

    // Any answer word, as whole words, longest first so "kids stuff" wins
    // over "kids".
    val wordPattern: Regex

    init {
        val words = LinkedHashMap<String, String>()
        // Built-ins first, so a user folder whose name reads the same
        // ("(Games)") can't take a built-in's answers.
        for (folder in folders.sortedBy { it.category == null }) words.putIfAbsent(answerWord(folder), folder.key)
        for ((singular, category) in AI_SORT_SINGULARS) {
            if (category.storageKey in words.values) words.putIfAbsent(singular, category.storageKey)
        }
        keyByWord = words
        wordPattern = Regex(
            words.keys.filter { it.isNotEmpty() }.sortedByDescending { it.length }
                .joinToString("|", prefix = "(?<![\\p{L}\\p{N}])(", postfix = ")(?![\\p{L}\\p{N}])") { Regex.escape(it) },
            RegexOption.IGNORE_CASE,
        )
    }

    /** The key of the folder [word] names, if any. */
    fun keyFor(word: String): String? = keyByWord[plainWords(word)]

    /**
     * A fingerprint of the instructions every prompt starts with: the folders
     * and what goes in each. It changes when a folder is added, deleted or
     * renamed, or a description reworded, and the AI's earlier choices are
     * then dropped so every app is sorted under the new instructions (see
     * `AppFolderStore`). Never 0, the "unset" a fresh install stores.
     */
    val version: Int get() = buildAiSortPrompt(emptyList(), this).hashCode().let { if (it == 0) 1 else it }

    companion object {
        val Default = AiSortFolders(activeAppFolders(emptyList(), emptySet()))

        /**
         * A user folder's name in letters, digits and spaces only, so the
         * answer reads the same with or without its brackets or emoji
         * ("Work (personal)" is "work personal"); its key if nothing's left.
         */
        fun answerWord(folder: AppFolderDef): String =
            folder.category?.storageKey ?: plainWords(folder.name).ifEmpty { folder.key }

        fun plainWords(text: String): String =
            text.replace(NOT_A_WORD, " ").trim().lowercase().take(AI_SORT_MAX_LABEL_CHARS)

        private val NOT_A_WORD = Regex("""[^\p{L}\p{N}-]+""")
    }
}

/**
 * The prompt for one batch: what each folder is for, then the apps, numbered.
 * Each built-in folder is described by what goes in it, because "Social" or
 * "Tools" alone leaves the small model to guess where messaging or a browser
 * belongs; a folder the user made has only its name to go on.
 */
internal fun buildAiSortPrompt(apps: List<AiSortInput>, folders: AiSortFolders = AiSortFolders.Default): String =
    buildString {
        appendLine("Put each Android app into exactly one folder. The folders:")
        val active = HashSet<AppCategory>()
        // The user's own folders first, since they asked for them.
        for (folder in folders.folders.sortedBy { it.category != null }) {
            val description = folder.category?.let { AI_SORT_FOLDER_DESCRIPTIONS[it] } ?: AI_SORT_USER_FOLDER
            folder.category?.let(active::add)
            appendLine("${AiSortFolders.answerWord(folder)}: $description")
        }
        appendLine()
        val examples = AI_SORT_EXAMPLES.filter { (_, category) -> category in active }
        if (examples.isNotEmpty()) {
            appendLine(
                examples.joinToString(", ", prefix = "Examples: ", postfix = ".") { (app, category) ->
                    "$app is ${category.storageKey}"
                },
            )
        }
        if (AppCategory.Tools in active) {
            appendLine("Choose tools only for a utility no other folder fits, and other only when nothing fits.")
        } else {
            appendLine("Choose other only when nothing fits.")
        }
        appendLine()
        appendLine("Answer with one line per app, as \"number: folder\", and nothing else.")
        appendLine()
        apps.forEachIndexed { index, app ->
            appendLine("${index + 1}. ${app.label.sanitizedForPrompt()} (${app.packageName})")
        }
    }

/**
 * Reads the model's answer back into a folder key for the apps in [apps] it
 * covers. Small models don't keep strictly to "number: folder": they echo
 * the app ("3. The Walking Dead (com.x): games"), drop the number, bold
 * things, or write a folder in the singular. So each line is read loosely.
 * The app is the one the leading number points to or, without one, the one
 * whose name comes first in the line. The folder is the one named right after
 * the number ("1: media (BBC News)"); otherwise the last folder named once the
 * app's own name, its package and anything in parentheses are taken out, so
 * an app named "BBC News" or "Google Home" isn't read as its own answer. A
 * line with no folder left is skipped, and so is an app the answer leaves
 * out; the caller decides what to do about those.
 */
internal fun parseAiSortAnswer(
    answer: String,
    apps: List<AiSortInput>,
    folders: AiSortFolders = AiSortFolders.Default,
): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    // Longest names first, so "Google Home" wins over "Home" where both start.
    val byLabel = apps.map { it to it.label.sanitizedForPrompt().lowercase() }
        .filter { (_, label) -> label.isNotEmpty() }
        .sortedByDescending { (_, label) -> label.length }
    for (line in answer.lineSequence()) {
        val numbered = AI_SORT_LEADING_NUMBER.find(line)
        val number = numbered?.groupValues?.get(1)?.toIntOrNull()
        val app = if (number != null) {
            apps.getOrNull(number - 1)
        } else {
            // The name that comes first is the app; a later one is the answer
            // ("CNN: news" with an app called News too).
            byLabel.mapNotNull { (app, label) -> wholeWords(label).find(line)?.let { app to it.range.first } }
                .minByOrNull { (_, at) -> at }?.first
        } ?: continue
        val key = answerIn(if (numbered != null) line.substring(numbered.range.last + 1) else line, app, folders)
            ?: continue
        result.putIfAbsent(app.id, key)
    }
    return result
}

/**
 * The folder [text] (a line after its number) gives for [app]. The app's
 * name, if echoed first, is skipped, and so is anything in parentheses and
 * the package, so "BBC News" or "Google Home" in an echo isn't read as the
 * answer. Then a folder named first is the answer ("media (BBC News)"), else
 * the last folder named ("The Walking Dead: games"). When nothing's left but
 * the app's name is itself a folder name ("1: news" for an app called News),
 * that name is the answer.
 */
private fun answerIn(text: String, app: AiSortInput, folders: AiSortFolders): String? {
    val label = app.label.sanitizedForPrompt()
    val trimmed = text.trimStart { !it.isLetterOrDigit() }
    // A folder whose name starts with the app's ("home stuff" for an app
    // called Home) is an answer, not an echo.
    folders.wordPattern.find(AiSortFolders.plainWords(trimmed))
        ?.takeIf { it.range.first == 0 && it.value.length > AiSortFolders.plainWords(label).length }
        ?.let { return folders.keyFor(it.value) }
    // Whole words only: an app called Learn doesn't eat "learning".
    val echoed = label.isNotEmpty() && wholeWords(label).find(trimmed)?.range?.first == 0
    var rest = if (echoed) trimmed.substring(label.length) else trimmed
    if (app.packageName.isNotEmpty()) rest = rest.replace(app.packageName, " ", ignoreCase = true)
    // A folder named first, read with its brackets and symbols as spaces, so
    // "1: work (personal)" finds a folder named "Work (personal)".
    val plain = AiSortFolders.plainWords(rest)
    folders.wordPattern.find(plain)?.takeIf { it.range.first == 0 }?.let { return folders.keyFor(it.value) }
    rest = rest.replace(AI_SORT_PARENTHESES, " ")
    // In a line found by the app's name rather than a number, the name may
    // come anywhere; take it out once.
    if (!echoed && label.isNotEmpty()) {
        val match = wholeWords(label).find(rest)
        if (match != null && folders.keyFor(label) == null) rest = rest.removeRange(match.range)
    }
    val named = folders.wordPattern.findAll(rest).toList()
    val first = named.firstOrNull()
    if (first != null && rest.substring(0, first.range.first).none { it.isLetterOrDigit() }) {
        return folders.keyFor(first.value)
    }
    named.lastOrNull()?.let { return folders.keyFor(it.value) }
    return if (echoed) folders.keyFor(label) else null
}

// [text] as whole words, in any case.
private fun wholeWords(text: String) =
    Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(text) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

private val AI_SORT_PARENTHESES = Regex("""\([^)]*\)""")

// Singulars a small model writes for a folder named in the plural.
private val AI_SORT_SINGULARS = listOf(
    "game" to AppCategory.Games,
    "tool" to AppCategory.Tools,
    "photo" to AppCategory.Photos,
    "pet" to AppCategory.Pets,
    "sport" to AppCategory.Sports,
)

// "12: games", "**12.** Games", "12) games": the app's number in the prompt.
private val AI_SORT_LEADING_NUMBER = Regex("""^\W*(\d+)\b""")

// Labels are the app's own text: keep one to a line and short.
private fun String.sanitizedForPrompt(): String =
    replace(Regex("""\s+"""), " ").trim().take(AI_SORT_MAX_LABEL_CHARS)

// Well-known apps with their folders, each kept only while its folder is in
// use. A small model follows examples more reliably than descriptions; Waze,
// Apple TV and Red Dead Redemption are here because it filed them under tools,
// other and media (maintainer, 2026-11-19).
private val AI_SORT_EXAMPLES = listOf(
    "Waze" to AppCategory.Transport,
    "Google Maps" to AppCategory.Transport,
    "Uber" to AppCategory.Transport,
    "Booking.com" to AppCategory.Travel,
    "AccuWeather" to AppCategory.Weather,
    "Apple TV" to AppCategory.Media,
    "Netflix" to AppCategory.Media,
    "Spotify" to AppCategory.Music,
    "Kindle" to AppCategory.Reading,
    "Audible" to AppCategory.Reading,
    "Google Photos" to AppCategory.Photos,
    "WhatsApp" to AppCategory.Social,
    "Robinhood" to AppCategory.Finance,
    "Google Wallet" to AppCategory.Finance,
    "Amazon" to AppCategory.Shopping,
    "Uber Eats" to AppCategory.Food,
    "Google Home" to AppCategory.Home,
    "Octopus Energy" to AppCategory.Home,
    "Duolingo" to AppCategory.Learning,
    "Drops" to AppCategory.Learning,
    "Coursera" to AppCategory.Learning,
    "YouTube Kids" to AppCategory.Kids,
    "ESPN" to AppCategory.Sports,
    "IRS2Go" to AppCategory.Government,
    "Rover" to AppCategory.Pets,
    "Gmail" to AppCategory.Productivity,
    "GitHub" to AppCategory.Code,
    "ChatGPT" to AppCategory.Ai,
    "Gemini" to AppCategory.Ai,
    "Termux" to AppCategory.Code,
    "Chrome" to AppCategory.Internet,
    "T-Mobile" to AppCategory.Phone,
    "Calculator" to AppCategory.Tools,
    "The Walking Dead" to AppCategory.Games,
    "Dysmantle" to AppCategory.Games,
    "Red Dead Redemption" to AppCategory.Games,
)

private const val AI_SORT_USER_FOLDER =
    "a folder the user made; put every app that fits its name here rather than in the folders below"

private val AI_SORT_FOLDER_DESCRIPTIONS = mapOf(
    AppCategory.Ai to "AI chatbots and assistants, AI image and writing tools",
    AppCategory.Code to "programming and coding, code editors and IDEs, terminals and SSH, Git and developer tools",
    AppCategory.Finance to "banking, payments, wallets and payment cards, money transfer, investing, budgeting, crypto, insurance",
    AppCategory.Food to "food and grocery delivery, restaurants, takeaway, recipes and cooking, coffee",
    AppCategory.Games to "games of any kind, including games from Netflix",
    AppCategory.Government to "government services, taxes, ID cards and driver's licenses, benefits, voting, city and council services",
    AppCategory.Health to "fitness, workouts, health, medical, meditation, sleep, period tracking",
    AppCategory.Home to "smart home, home devices and security cameras, thermostats, property, real estate, rentals, household utilities and energy suppliers",
    AppCategory.Internet to "web browsers, VPNs, Wi-Fi and network tools, internet providers",
    AppCategory.Kids to "apps made for children, kids' games and learning, parental controls",
    AppCategory.Learning to "language learning, e-learning and online courses, education, school and university, reference",
    AppCategory.Media to "TV and movie apps, video streaming and players",
    AppCategory.Phone to "the phone app and calls, mobile carriers and phone plans, SIM and eSIM, telecoms",
    AppCategory.Music to "music streaming and players, podcasts, radio, making music",
    AppCategory.News to "news, magazines, newspapers",
    AppCategory.Pets to "pets and animals, pet care, vets, wildlife, birdwatching",
    AppCategory.Photos to "camera, photo gallery, photo editing and printing",
    AppCategory.Productivity to "email, calendar, notes, documents, office, tasks, work",
    AppCategory.Reading to "books and e-books, audiobooks, comics and manga, libraries, read-it-later and reading apps",
    AppCategory.Shopping to "shopping, marketplaces, coupons",
    AppCategory.Social to "messaging, chat, calls, social networks, dating, communities",
    AppCategory.Sports to "sports scores and news, teams and leagues, fantasy sports, sports streaming",
    AppCategory.Tools to "files, calculators, clocks, settings, keyboards, other utilities",
    AppCategory.Transport to "maps, navigation and traffic, public transit, trains and buses, rideshare and taxis, parking, EV charging, bikes and scooters, cars",
    AppCategory.Travel to "airlines and flights, airports and border control, hotels, travel booking, car rental",
    AppCategory.Weather to "weather forecasts, radar, air quality",
    AppCategory.Other to "anything that fits none of the above",
)

/** The instructions' fingerprint with the built-in folders, none deleted. */
internal val AI_SORT_PROMPT_VERSION: Int get() = AiSortFolders.Default.version

/** Apps asked about per model call: enough to amortize it, few enough to stay accurate. */
internal const val AI_SORT_BATCH_SIZE = 15
private const val AI_SORT_MAX_LABEL_CHARS = 60

// Waits after AICore says it's busy: doubling from 2 s, at most a minute, and
// after five in a row sorting pauses until the next return to Home.
internal const val AI_SORT_FIRST_RETRY_MILLIS = 2_000L
internal const val AI_SORT_MAX_RETRY_MILLIS = 60_000L
internal const val AI_SORT_MAX_BUSY_RETRIES = 5

// Answers an app may be left out of before it goes to Other, so one garbled
// answer doesn't file a batch in Other but a model that never covers an app
// doesn't ask about it forever.
internal const val AI_SORT_MAX_MISSES = 2
private const val AI_SORT_MAX_OUTPUT_TOKENS = 256
