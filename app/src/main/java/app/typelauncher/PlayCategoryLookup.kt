package app.typelauncher

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * "Sort using Google Play": an app's category from its public Google Play
 * page, looked up from the phone, only for apps Google Play installed and only
 * once the user has agreed (see SPEC "App folders"). No server of ours is
 * involved, and nothing about the phone or its other apps is sent.
 */
internal sealed interface PlayLookup {
    /** The page names this category, a Play code such as `FINANCE` or `GAME_PUZZLE`. */
    data class Found(val category: String) : PlayLookup

    /** Google Play has no page for the app, or one without a category. */
    data object NotListed : PlayLookup

    /** Google Play asked us to slow down; try again later. */
    data object SlowDown : PlayLookup

    /** No answer (no network, a timeout, an unexpected reply); try again later. */
    data object Failed : PlayLookup
}

/** Looks up one app's Google Play category. */
internal fun interface PlayCategoryFetcher {
    suspend fun fetch(packageName: String): PlayLookup
}

/**
 * Reads the category from the app's Google Play page, which carries it in its
 * schema.org data as `"applicationCategory":"TRAVEL_AND_LOCAL"`. The page is
 * asked for in English, so it reads the same everywhere, and for [country] (the
 * phone's), so apps sold only there are found. Pages are about 200 KB
 * compressed. Never logs the package name.
 */
internal class HttpPlayCategoryFetcher(
    private val country: () -> String?,
    private val dispatcher: CoroutineDispatcher,
) : PlayCategoryFetcher {
    override suspend fun fetch(packageName: String): PlayLookup = withContext(dispatcher) {
        val connection = try {
            URL(playPageUrl(packageName, country())).openConnection() as HttpURLConnection
        } catch (exception: IOException) {
            LauncherDebugLog.event("playSort connect failed: %s", exception.javaClass.simpleName)
            return@withContext PlayLookup.Failed
        }
        try {
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val page = connection.inputStream.bufferedReader().use { it.readText() }
                    parsePlayCategory(page)?.let { PlayLookup.Found(it) } ?: PlayLookup.NotListed
                }
                HttpURLConnection.HTTP_NOT_FOUND -> PlayLookup.NotListed
                HTTP_TOO_MANY_REQUESTS, HttpURLConnection.HTTP_UNAVAILABLE -> PlayLookup.SlowDown
                else -> {
                    LauncherDebugLog.event("playSort unexpected status=%s", code)
                    PlayLookup.Failed
                }
            }
        } catch (exception: IOException) {
            LauncherDebugLog.event("playSort read failed: %s", exception.javaClass.simpleName)
            PlayLookup.Failed
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}

internal fun playPageUrl(packageName: String, country: String?): String {
    val id = URLEncoder.encode(packageName, Charsets.UTF_8)
    val region = country?.takeIf { it.length == 2 }?.let { "&gl=" + it.uppercase() }.orEmpty()
    return "https://play.google.com/store/apps/details?id=$id&hl=en$region"
}

private val PLAY_CATEGORY = Regex(""""applicationCategory"\s*:\s*"([A-Z_]+)"""")

/** The Play category a Google Play page names, or null when it names none. */
internal fun parsePlayCategory(page: String): String? = PLAY_CATEGORY.find(page)?.groupValues?.get(1)

/**
 * The folder for a Google Play category, or null where the category says too
 * little to choose one (Lifestyle, say). Play's categories are coarser than our
 * folders, so this is a fallback below our own rules: AI apps, rewards and job
 * boards, for instance, sit in Productivity, Shopping and Business on Play.
 */
internal fun folderForPlayCategory(category: String): AppCategory? {
    if (category.startsWith("GAME")) return AppCategory.Games
    return when (category) {
        "ART_AND_DESIGN" -> AppCategory.Design
        "AUTO_AND_VEHICLES", "MAPS_AND_NAVIGATION" -> AppCategory.Transport
        "BEAUTY", "SHOPPING" -> AppCategory.Shopping
        "BOOKS_AND_REFERENCE", "COMICS" -> AppCategory.Reading
        "BUSINESS", "PRODUCTIVITY" -> AppCategory.Office
        "COMMUNICATION", "DATING", "SOCIAL" -> AppCategory.Social
        "EDUCATION" -> AppCategory.Learning
        "ENTERTAINMENT", "VIDEO_PLAYERS" -> AppCategory.Media
        "EVENTS" -> AppCategory.Events
        "FINANCE" -> AppCategory.Finance
        "FOOD_AND_DRINK" -> AppCategory.Food
        "HEALTH_AND_FITNESS", "MEDICAL" -> AppCategory.Health
        "HOUSE_AND_HOME" -> AppCategory.Home
        "MUSIC_AND_AUDIO" -> AppCategory.Music
        "NEWS_AND_MAGAZINES" -> AppCategory.News
        "PARENTING" -> AppCategory.Kids
        "PERSONALIZATION", "TOOLS" -> AppCategory.Tools
        "PHOTOGRAPHY" -> AppCategory.Photos
        "SPORTS" -> AppCategory.Sports
        "TRAVEL_AND_LOCAL" -> AppCategory.Travel
        "WEATHER" -> AppCategory.Weather
        else -> null
    }
}

/** The installer Android records for Google Play. */
internal const val GOOGLE_PLAY_INSTALLER = "com.android.vending"

internal enum class PlaySortPhase {
    /** Nothing running. */
    Idle,

    /** Looking apps up. */
    Sorting,

    /** Google Play asked us to slow down, or there was no answer; resumes later. */
    Paused,

    /** Finished; the note says how it went until the user taps Done. */
    Done,
}

/**
 * An app "Sort using Google Play" could look up, whether Google Play installed
 * it, and whether the user kept it out before (unticked, or "Not now").
 */
@androidx.compose.runtime.Immutable
internal data class PlaySortCandidate(val app: InstalledApp, val fromPlay: Boolean, val declined: Boolean = false)

/** What "Sort using Google Play" shows: the offer, its progress, and its result. */
@androidx.compose.runtime.Immutable
internal data class PlaySortState(
    // Apps the rules don't place and Google Play hasn't been asked about.
    val candidates: List<PlaySortCandidate> = emptyList(),
    val showOffer: Boolean = false,
    val phase: PlaySortPhase = PlaySortPhase.Idle,
    val done: Int = 0,
    val total: Int = 0,
    // Of the apps this run looked up: those Google Play gave a folder, and those it didn't.
    val sorted: Int = 0,
    val unsorted: Int = 0,
    val isAutoEnabled: Boolean = false,
    val hasUsed: Boolean = false,
) {
    /** The apps the offer lists as tickable: those Google Play installed. */
    val offered: List<PlaySortCandidate> get() = candidates.filter { it.fromPlay }
}

/** The gap between lookups, so a first sort of tens of apps doesn't arrive at once. */
internal const val PLAY_SORT_SPACING_MILLIS = 1_000L

/** How long to wait after Google Play asks us to slow down, or doesn't answer. */
internal const val PLAY_SORT_RETRY_MILLIS = 10 * 60_000L

/**
 * The phone's country as two letters, for finding apps sold only there: the
 * mobile network's, else the SIM's, else the language setting's. Null when
 * none is known. Reads no location.
 */
internal fun phoneCountry(context: android.content.Context): String? {
    val telephony = context.getSystemService(android.telephony.TelephonyManager::class.java)
    return listOfNotNull(telephony?.networkCountryIso, telephony?.simCountryIso, java.util.Locale.getDefault().country)
        .firstOrNull { it.length == 2 }
}

/** The installer Android records for [packageName], or null when it records none. */
internal fun installerPackage(context: android.content.Context, packageName: String): String? = try {
    context.packageManager.getInstallSourceInfo(packageName).installingPackageName
} catch (exception: android.content.pm.PackageManager.NameNotFoundException) {
    // Uninstalled since the list was read: it simply isn't offered.
    LauncherDebugLog.event("playSort installer unknown: %s", exception.javaClass.simpleName)
    null
}
