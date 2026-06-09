package app.typelauncher

/**
 * Ordered by estimated global install prevalence. The prefill pass walks this
 * list and docks the first `maxSlots` entries whose package is installed.
 */
internal val POPULAR_APP_PACKAGES = listOf(
    // Google core — pre-installed on most Android devices
    "com.android.chrome",
    "com.google.android.gm",
    "com.google.android.apps.maps",
    "com.google.android.youtube",
    "com.google.android.dialer",
    "com.google.android.apps.messaging",
    // AOSP defaults used by many OEMs
    "com.android.dialer",
    "com.android.mms",
    // Global social / communication
    "com.whatsapp",
    "com.facebook.katana",
    "com.instagram.android",
    "com.zhiliaoapp.musically",
    "com.snapchat.android",
    "org.telegram.messenger",
    "com.twitter.android",
    "com.discord",
    "org.thoughtcrime.securesms",
    // Entertainment / media
    "com.spotify.music",
    "com.netflix.mediaclient",
    "tv.twitch.android.app",
    "com.hulu.plus",
    "com.amazon.avod.thirdpartyclient",
    "com.disney.disneyplus",
    "com.apple.android.music",
    "com.pandora.android",
    "com.soundcloud.android",
    // Productivity / work
    "us.zoom.videomeetings",
    "com.microsoft.teams",
    "com.microsoft.office.outlook",
    "com.linkedin.android",
    "com.skype.raider",
    "com.google.android.apps.meetings",
    "com.google.android.apps.tachyon",
    "com.google.android.apps.docs",
    "com.google.android.calendar",
    "com.google.android.apps.photos",
    "com.google.android.keep",
    // Shopping / payments / transport
    "com.amazon.mShop.android.shopping",
    "com.paypal.android.p2pmobile",
    "com.venmo",
    "com.squareup.cash",
    "com.ubercab",
    "com.ubercab.eats",
    "com.dd.doordash",
    // Finance
    "com.robinhood.android",
    "com.coinbase.android",
    // Social
    "com.reddit.frontpage",
    "com.pinterest",
    "com.tumblr",
)

/**
 * Docks the first [maxSlots] installed apps from [POPULAR_APP_PACKAGES].
 * Only personal-profile apps are considered so work-profile duplicates don't
 * consume dock slots during the one-time prefill.
 */
internal fun prefillDock(
    installedApps: List<InstalledApp>,
    dockedAppStore: DockedAppStore,
    maxSlots: Int,
) {
    val byPackage = installedApps
        .filter { !it.isWorkApp }
        .groupBy { it.packageName }
    for (packageName in POPULAR_APP_PACKAGES) {
        if (dockedAppStore.dockedAppIds.size >= maxSlots) break
        val app = byPackage[packageName]?.firstOrNull() ?: continue
        dockedAppStore.dock(app.id, columnCount = (maxSlots + 1).coerceAtLeast(1))
    }
}
