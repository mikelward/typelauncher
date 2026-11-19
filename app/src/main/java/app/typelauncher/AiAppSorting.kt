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
 * The prompt for one batch: what each folder is for, then the apps, numbered.
 * Each folder is described by what goes in it, because "Social" or "Tools"
 * alone leaves the small model to guess where messaging or a browser belongs.
 */
internal fun buildAiSortPrompt(apps: List<AiSortInput>): String = buildString {
    appendLine("Put each Android app into exactly one folder. The folders:")
    for ((category, description) in AI_SORT_FOLDER_DESCRIPTIONS) {
        appendLine("${category.storageKey}: $description")
    }
    appendLine()
    appendLine("Examples: $AI_SORT_EXAMPLES.")
    appendLine("Choose tools only for a utility no other folder fits, and other only when nothing fits.")
    appendLine()
    appendLine("Answer with one line per app, as \"number: folder\", and nothing else.")
    appendLine()
    apps.forEachIndexed { index, app ->
        appendLine("${index + 1}. ${app.label.sanitizedForPrompt()} (${app.packageName})")
    }
}

/**
 * Reads the model's "number: folder" lines back into a folder for each app in
 * [apps]. A line it can't read, or a folder name it made up, files that app in
 * Other rather than leaving it to be asked again forever; so does an app the
 * answer leaves out.
 */
internal fun parseAiSortAnswer(answer: String, apps: List<AiSortInput>): Map<String, AppCategory> {
    val byNumber = HashMap<Int, AppCategory>()
    for (line in answer.lineSequence()) {
        val match = AI_SORT_LINE.find(line) ?: continue
        val number = match.groupValues[1].toIntOrNull() ?: continue
        val category = AppCategory.fromStorageKey(match.groupValues[2].lowercase()) ?: AppCategory.Other
        byNumber.putIfAbsent(number, category)
    }
    return apps.withIndex().associate { (index, app) -> app.id to (byNumber[index + 1] ?: AppCategory.Other) }
}

// "12: games", "12. Games", "12 - games", with optional markdown around it.
private val AI_SORT_LINE = Regex("""^\W*(\d+)\W+([A-Za-z]+)""")

// Labels are the app's own text: keep one to a line and short.
private fun String.sanitizedForPrompt(): String =
    replace(Regex("""\s+"""), " ").trim().take(AI_SORT_MAX_LABEL_CHARS)

// Well-known apps with their folders. A small model follows examples more
// reliably than descriptions; Waze and Apple TV are here because it filed them
// under tools and other (maintainer, 2026-11-19).
private const val AI_SORT_EXAMPLES =
    "Waze is travel, Google Maps is travel, Apple TV is media, Netflix is media, Spotify is media, " +
        "WhatsApp is social, Robinhood is finance, Google Wallet is finance, Amazon is shopping, " +
        "Google Home is home, Gmail is productivity, Chrome is tools, Calculator is tools"

private val AI_SORT_FOLDER_DESCRIPTIONS = listOf(
    AppCategory.Finance to "banking, payments, wallets and payment cards, money transfer, investing, budgeting, crypto",
    AppCategory.Games to "games of any kind",
    AppCategory.Health to "fitness, workouts, health, medical, meditation, sleep, period tracking",
    AppCategory.Home to "smart home, home devices and security cameras, thermostats, property, real estate, rentals, household utilities",
    AppCategory.Media to "TV and movie apps, video streaming, music, podcasts, photos, camera, gallery, books, audiobooks",
    AppCategory.News to "news, magazines, newspapers",
    AppCategory.Productivity to "email, calendar, notes, documents, office, tasks, work, learning, education",
    AppCategory.Shopping to "shopping, marketplaces, food delivery, groceries, coupons",
    AppCategory.Social to "messaging, chat, calls, social networks, dating, communities",
    AppCategory.Tools to "browsers, files, calculators, clocks, weather, settings, keyboards, other utilities",
    AppCategory.Travel to "maps, navigation and traffic, rideshare, transit, parking, airlines, hotels, travel booking, cars",
    AppCategory.Other to "anything that fits none of the above",
)

/**
 * A fingerprint of the instructions every prompt starts with: the folders and
 * what goes in each. It changes when a folder is added or a description is
 * reworded, and the AI's earlier choices are then dropped so every app is
 * sorted under the new instructions (see `AppFolderStore`).
 */
internal val AI_SORT_PROMPT_VERSION: Int = buildAiSortPrompt(emptyList()).hashCode().let { if (it == 0) 1 else it }

/** Apps asked about per model call: enough to amortize it, few enough to stay accurate. */
internal const val AI_SORT_BATCH_SIZE = 15
private const val AI_SORT_MAX_LABEL_CHARS = 60

// Waits after AICore says it's busy: doubling from 2 s, at most a minute, and
// after five in a row sorting pauses until the next return to Home.
internal const val AI_SORT_FIRST_RETRY_MILLIS = 2_000L
internal const val AI_SORT_MAX_RETRY_MILLIS = 60_000L
internal const val AI_SORT_MAX_BUSY_RETRIES = 5
private const val AI_SORT_MAX_OUTPUT_TOKENS = 256
