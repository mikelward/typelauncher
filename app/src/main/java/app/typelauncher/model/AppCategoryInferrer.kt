package app.typelauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.IOException

internal const val CATEGORY_OVERRIDES_ASSET = "category_overrides.txt"

// TODO: If appCategory + APP_* + asset overrides coverage still proves sparse,
// layer on (in order of cost):
//   1. ACTION_VIEW mime-type probing for image/*, audio/*, video/*, text/*.
//   2. requestedPermissions heuristics (CAMERA -> Photos, RECORD_AUDIO + comm
//      perms -> Messaging) as a last resort.
//   3. User override per app via the long-press menu, persisted in a new
//      SharedPreferences store layered on top of the inferred map.

/**
 * Infers an [AppCategory] for each installed app.
 *
 * Three-pass strategy, highest-precedence first:
 *   1. [ApplicationInfo.category] (declared by the app developer in their
 *      manifest, available since API 26). The caller-supplied
 *      [manifestCategories] is consulted first so work-profile apps (which
 *      the launcher's owner-profile [PackageManager] can't see) still get
 *      pass-1 coverage — `loadInstalledApps` populates the map from
 *      `LauncherActivityInfo.applicationInfo` per profile. For packages not
 *      in the map, fall back to `PackageManager.getApplicationInfo` so
 *      callers without a [LauncherApps] handle still get owner-profile
 *      coverage.
 *   2. Android's `Intent.CATEGORY_APP_*` launcher categories: query the
 *      package manager once per category and slot the resolved packages.
 *      Owner-profile only — `PackageManager.queryIntentActivities` doesn't
 *      see other users, and `LauncherApps` has no equivalent per-profile
 *      query for arbitrary launcher categories. Work-profile apps that miss
 *      pass 1 won't be caught here; they fall through to pass 3 or `Other`.
 *   3. A curated overrides map shipped as `assets/category_overrides.txt`,
 *      for categories Android has no built-in slot for (Finance, banking,
 *      regional payment apps, ...) and apps that miss the manifest signal.
 *      Profile-agnostic — keyed on package name only.
 *
 * Anything still unmapped lands in [AppCategory.Other]. The result is keyed
 * by [InstalledApp.id] so personal/work duplicates of the same package don't
 * collide.
 */
internal fun inferCategories(
    context: Context,
    apps: List<InstalledApp>,
    manifestCategories: Map<String, Int> = emptyMap(),
): Map<String, AppCategory> {
    if (apps.isEmpty()) return emptyMap()
    val packageManager = context.packageManager
    val packageCategories = HashMap<String, AppCategory>()

    for (packageName in apps.map { it.packageName }.toSet()) {
        if (packageName in packageCategories) continue
        val rawCategory = manifestCategories[packageName]
            ?: runCatching {
                packageManager.getApplicationInfo(packageName, 0).category
            }.getOrDefault(ApplicationInfo.CATEGORY_UNDEFINED)
        appCategoryFromManifest(rawCategory)?.let { packageCategories[packageName] = it }
    }

    for ((intentCategory, appCategory) in APP_INTENT_CATEGORIES) {
        val probe = Intent(Intent.ACTION_MAIN).addCategory(intentCategory)
        val resolved = runCatching { packageManager.queryIntentActivities(probe, 0) }
            .getOrDefault(emptyList())
        for (info in resolved) {
            val packageName = info.activityInfo?.packageName ?: continue
            packageCategories.putIfAbsent(packageName, appCategory)
        }
    }

    val overrides = loadCategoryOverrides(context)
    for (app in apps) {
        if (app.packageName in packageCategories) continue
        overrides[app.packageName]?.let { packageCategories[app.packageName] = it }
    }

    return apps.associate { app ->
        app.id to (packageCategories[app.packageName] ?: AppCategory.Other)
    }
}

/**
 * Loads the curated overrides map from the asset bundled with the app. Memoised
 * across calls, since the file is immutable for the life of the process. Test
 * code can clear the cache with [resetCategoryOverridesCacheForTest].
 */
internal fun loadCategoryOverrides(context: Context): Map<String, AppCategory> {
    cachedOverrides?.let { return it }
    val parsed = try {
        context.assets.open(CATEGORY_OVERRIDES_ASSET).bufferedReader().use { reader ->
            parseCategoryOverrides(reader.readText())
        }
    } catch (_: IOException) {
        emptyMap()
    }
    cachedOverrides = parsed
    return parsed
}

internal fun parseCategoryOverrides(text: String): Map<String, AppCategory> {
    val result = HashMap<String, AppCategory>()
    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val separator = line.indexOf('=')
        if (separator <= 0 || separator == line.lastIndex) continue
        val packageName = line.substring(0, separator).trim()
        val categoryName = line.substring(separator + 1).trim()
        if (packageName.isEmpty() || categoryName.isEmpty()) continue
        val category = runCatching { AppCategory.valueOf(categoryName) }.getOrNull() ?: continue
        result[packageName] = category
    }
    return result
}

internal fun resetCategoryOverridesCacheForTest() {
    cachedOverrides = null
}

@Volatile
private var cachedOverrides: Map<String, AppCategory>? = null

private fun appCategoryFromManifest(manifestCategory: Int): AppCategory? = when (manifestCategory) {
    ApplicationInfo.CATEGORY_GAME -> AppCategory.Games
    ApplicationInfo.CATEGORY_AUDIO -> AppCategory.Music
    ApplicationInfo.CATEGORY_VIDEO -> AppCategory.Video
    ApplicationInfo.CATEGORY_IMAGE -> AppCategory.Photos
    ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.Social
    ApplicationInfo.CATEGORY_NEWS -> AppCategory.News
    ApplicationInfo.CATEGORY_MAPS -> AppCategory.Maps
    ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppCategory.Productivity
    ApplicationInfo.CATEGORY_ACCESSIBILITY -> AppCategory.Accessibility
    else -> null
}

private val APP_INTENT_CATEGORIES: List<Pair<String, AppCategory>> = listOf(
    Intent.CATEGORY_APP_BROWSER to AppCategory.Browser,
    Intent.CATEGORY_APP_EMAIL to AppCategory.Email,
    Intent.CATEGORY_APP_GALLERY to AppCategory.Photos,
    Intent.CATEGORY_APP_MAPS to AppCategory.Maps,
    Intent.CATEGORY_APP_MUSIC to AppCategory.Music,
    Intent.CATEGORY_APP_MESSAGING to AppCategory.Messaging,
    Intent.CATEGORY_APP_CALENDAR to AppCategory.Calendar,
    Intent.CATEGORY_APP_CONTACTS to AppCategory.Contacts,
    Intent.CATEGORY_APP_FILES to AppCategory.Files,
    Intent.CATEGORY_APP_FITNESS to AppCategory.Fitness,
)
