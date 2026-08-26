# R8 keep rules for the minified builds — every CI-built APK and the Play AAB
# (isMinifyEnabled = isCiBuild in app/build.gradle.kts).
#
# R8 runs fully optimizing here: shrink, optimize, obfuscate. Google Play's
# code-optimization requirement is defined as exactly that configuration —
# minification and resource shrinking on, the -optimize baseline in use, and
# -dontoptimize / -dontshrink / -dontobfuscate all absent — so none of those
# flags may come back. Stack traces stay readable because the Crashlytics
# Gradle plugin uploads the mapping file for every minified variant.
#
# Keep this list tight — each rule names why it exists.

# Deobfuscation needs the frame's file and line to survive; -renamesourcefile
# then replaces the original file name (which would leak the pre-obfuscation
# class name) with a constant Crashlytics ignores.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Settings persisted as enum names ----------------------------------------
# DockedAppStore stores these in SharedPreferences as the enum constant's *name*
# and reads them back through Enum.valueOf (enumOrDefault / AppListLayout.valueOf).
# If obfuscation ever renamed a constant, name() would rename with it and every
# value an earlier build wrote would stop resolving, falling silently back to the
# default — the user's theme, icon shape, dock layout and sort order all reset on
# an update. R8 does preserve enum names today (verified: the constant strings are
# still in the release dex), but that is its choice, not a guarantee, and the
# failure would be silent and unrecoverable. Pin them.
#
# Note the package: these live in app.typelauncher, not app.typelauncher.model —
# the model/ directory does not match the package declaration.
-keepclassmembers enum app.typelauncher.AppListSortOrder,
                       app.typelauncher.AppListLayout,
                       app.typelauncher.AppListDataOrdering,
                       app.typelauncher.DockLayout,
                       app.typelauncher.ThemeMode,
                       app.typelauncher.CallMethod,
                       app.typelauncher.IconShape,
                       app.typelauncher.IconTheme,
                       app.typelauncher.KeyboardReservationSource {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Class names that reach telemetry and the bug report ---------------------
# launcherTelemetryKeys sends the *simple class name* of the current destination,
# agenda state and Play-update state as Crashlytics keys (TelemetryKeys.kt); the
# agenda_initial_load performance trace sends the agenda state the same way
# (LauncherViewModel.kt); LauncherDebugLog puts two of them in the shared bug
# report. Obfuscation turns documented values like Home, Events and NotAvailable
# into opaque names that also change from release to release, so a Crashlytics
# filter or a cross-version trace comparison stops meaning anything. Worse, most
# of these are empty data objects, which R8 is otherwise free to merge — that
# reports the wrong state, not merely an unreadable one. Keep the names; an
# unused class may still be shrunk away.
-keepnames class app.typelauncher.LauncherDestination*
-keepnames class app.typelauncher.AgendaUiState*
-keepnames class app.typelauncher.PlayUpdateState*
