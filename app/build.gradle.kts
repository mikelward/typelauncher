import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
}

// Emit Compose Compiler stability and metrics reports under
// `app/build/compose_compiler/` on every Kotlin compilation. Inspect
// `app_release-classes.txt` to see which classes the compiler considers
// `stable`, `unstable`, or `runtime`, and `app_release-composables.txt`
// to see which composables are `skippable` / `restartable`. Generated
// build output, not checked in.
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}

val isCiBuild: Boolean = providers.environmentVariable("CI")
    .map { value -> value.equals("true", ignoreCase = true) }
    .getOrElse(false)

// A debug build made outside CI is `.dev`, not `.debug`, so it co-installs
// beside the tester build Firebase distributes instead of fighting it for one
// package name. The two are signed by different keys — CI's stable debug
// keystore vs. the developer's own — so sharing an ID isn't an upgrade, it's an
// INSTALL_FAILED_UPDATE_INCOMPATIBLE that forces an uninstall to switch between
// them. CI keeps `.debug`, leaving Firebase App Distribution untouched.
val debugApplicationIdSuffix = if (isCiBuild) ".debug" else ".dev"
val debugApplicationId = "app.typelauncher$debugApplicationIdSuffix"

// Firebase Crashlytics + Performance Monitoring need google-services.json to be
// present at app/google-services.json. The file isn't checked in (it identifies
// the Firebase project; the SDK still relies on the APK signature for trust),
// so forks and the sandbox without one build cleanly without telemetry — the
// plugins are skipped and LauncherTelemetry's wrapper no-ops at runtime when no
// FirebaseApp is initialized. See docs/firebase-telemetry.md.
val firebaseConfigFile = file("google-services.json")
val hasFirebaseConfig = firebaseConfigFile.exists()
if (hasFirebaseConfig) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
    apply(plugin = libs.plugins.firebase.crashlytics.get().pluginId)
    val firebaseConfig = firebaseConfigFile.readText()
    // The Google Services plugin hard-fails any build whose application ID has no
    // matching client ("No matching client found for package name ..."), so the
    // debug variant needs the same treatment the release variant already gets
    // below. app.typelauncher.dev deliberately has no client: a build from a
    // developer's machine should not file crashes into the shared project beside
    // real tester data, and nobody should have to register an extra Firebase app
    // just to build. Telemetry stays dormant in local builds exactly as it does
    // in a checkout with no config. Register app.typelauncher.dev and this stops
    // applying, wiring Firebase up for local builds too.
    //
    // The !isCiBuild clause matters: in CI a missing debug client means the
    // GOOGLE_SERVICES_JSON secret is stale, and the plugin's hard failure is the
    // signal the setup docs promise. Without it CI would take this bypass as well
    // and quietly distribute a tester APK with no Crashlytics.
    if (!isCiBuild && !firebaseConfig.contains("\"$debugApplicationId\"")) {
        // Disabling the task stops it regenerating, but Gradle does not delete a
        // disabled task's earlier output. Any checkout that has ever built the
        // tester variant — a local `CI=true` run, or any build from before the
        // `.dev` suffix existed — still holds the tester project's google_app_id
        // under build/generated/res/, and the resource merge will happily package
        // it into the `.dev` APK. Firebase would then initialize in a local build
        // and report to the shared tester project: precisely what this guard is
        // for. So purge that directory ahead of the merge, not just skip the
        // regeneration. (Two paths: AGP names the directory after the task;
        // older versions used google-services/<variant>.)
        val purgeForeignFirebaseResources = tasks.register<Delete>("purgeDebugGoogleServicesResources") {
            description = "Deletes Firebase resources generated for a different application ID."
            delete(
                layout.buildDirectory.dir("generated/res/processDebugGoogleServices"),
                layout.buildDirectory.dir("generated/res/google-services/debug"),
            )
        }
        afterEvaluate {
            tasks.matching {
                it.name in setOf(
                    "processDebugGoogleServices",
                    "injectCrashlyticsMappingFileIdDebug",
                    "uploadCrashlyticsMappingFileDebug",
                )
            }.configureEach {
                enabled = false
            }
            tasks.matching { it.name == "mergeDebugResources" }.configureEach {
                dependsOn(purgeForeignFirebaseResources)
            }
        }
    }
    // If google-services.json has no release client (app.typelauncher), disable the
    // release processing task so bundleRelease doesn't fail. When a release client is
    // present the task runs normally and Firebase/Crashlytics are wired up for production.
    val hasReleaseClient = firebaseConfig.contains("\"app.typelauncher\"")
    if (!hasReleaseClient) {
        afterEvaluate {
            // processReleaseGoogleServices generates gmpAppId/release.txt; disabling
            // it means that file is never created. AGP 9's strict input validation
            // then rejects uploadCrashlyticsMappingFileRelease at configuration time
            // because its declared appIdFile input is missing, so we must disable
            // that task too.
            tasks.matching {
                it.name in setOf(
                    "processReleaseGoogleServices",
                    "uploadCrashlyticsMappingFileRelease",
                )
            }.configureEach {
                enabled = false
            }
        }
    }
}

fun gitOutput(vararg args: String, fallback: String): String =
    try {
        val output = providers.exec {
            commandLine("git", *args)
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
        output.ifEmpty { fallback }
    } catch (_: Exception) {
        fallback
    }

val gitCommitCount: Int =
    gitOutput("rev-list", "--count", "HEAD", fallback = "1").toIntOrNull() ?: 1
val gitShortSha: String = gitOutput("rev-parse", "--short", "HEAD", fallback = "unknown")
val gitBranchName: String = providers.environmentVariable("GITHUB_REF_NAME")
    .orElse(gitOutput("rev-parse", "--abbrev-ref", "HEAD", fallback = "unknown"))
    .get()
// `git status --porcelain` is empty for a clean tree, so we can't route this
// through `gitOutput` — its empty-means-fallback semantics would map clean to
// "dirty". Inline the exec, and *don't* ignore the exit code: a non-zero exit
// (e.g. running from a tarball with no `.git`) should fall through to the
// conservative "assume dirty" branch rather than being misread as clean.
val isGitWorkingTreeDirty: Boolean =
    try {
        providers.exec {
            commandLine("git", "status", "--porcelain")
        }.standardOutput.asText.get().trim().isNotEmpty()
    } catch (_: Exception) {
        true
    }
val baseVersionName = "1.0"
// One badged icon per build, resolved at manifest-merge time. The Play build is
// plain; the CI debug build Firebase distributes wears a "DEBUG" bar; any build
// outside CI wears the "DEV" bar. Both bars use the same yellow plate and dark
// lettering — only the word differs. Previously the tester build was plain too,
// so it was indistinguishable from Play on the home screen, which is the one
// pairing most likely to be installed together since testers get both.
val devLauncherIcon = "@mipmap/ic_launcher_local"
val devLauncherRoundIcon = "@mipmap/ic_launcher_round_local"
val releaseLauncherIcon = if (isCiBuild) "@mipmap/ic_launcher" else devLauncherIcon
val releaseLauncherRoundIcon = if (isCiBuild) "@mipmap/ic_launcher_round" else devLauncherRoundIcon
val debugLauncherIcon = if (isCiBuild) "@mipmap/ic_launcher_debug" else devLauncherIcon
val debugLauncherRoundIcon = if (isCiBuild) "@mipmap/ic_launcher_round_debug" else devLauncherRoundIcon

// The DEV badge on the local icon, said again in the name beside it — the badge
// is easy to miss at icon size, and the home-role picker and app list are text.
// Three builds can co-exist on one phone: the Play build (app.typelauncher), the
// CI-built tester Firebase ships (app.typelauncher.debug), and a local APK. Only
// the Play build keeps the localized @string/app_name; the tester build is "Type
// Launcher Debug", and anything built outside CI — either build type — is "Type
// Launcher Dev". The two badged labels are manifest literals on purpose: they
// mark a build, never reach a store listing, and are not translated.
val devAppLabel = "Type Launcher Dev"
val releaseAppLabel = if (isCiBuild) "@string/app_name" else devAppLabel
val debugAppLabel = if (isCiBuild) "Type Launcher Debug" else devAppLabel
val buildConfiguredAtMillis = System.currentTimeMillis()
val localBuildBranch = if (isCiBuild) "" else gitBranchName
val localBuildSha = if (isCiBuild) "" else gitShortSha
val localBuildDirty = !isCiBuild && isGitWorkingTreeDirty
val localBuildTimeMillis = if (isCiBuild) 0L else buildConfiguredAtMillis

fun buildConfigString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

// Debug builds identify their source in the search hint: dirty worktrees are
// explicitly non-reproducible, main builds map to the monotonic versionCode,
// and clean branch builds map back to their commit SHA.
val debugSearchPlaceholderSuffix = when {
    isGitWorkingTreeDirty -> " (dirty)"
    gitBranchName == "main" -> " (v$gitCommitCount)"
    else -> " ($gitShortSha)"
}

abstract class InstallAndRunPersonalDebugTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    abstract val apkFile: RegularFileProperty

    /**
     * The debug APK's application ID, which depends on the environment — `.dev`
     * for a local build, `.debug` in CI — so it can't be a compile-time constant.
     * Wrong value here and the task uninstalls (or launches) the wrong package.
     */
    @get:Input
    abstract val applicationId: Property<String>

    @TaskAction
    fun installAndRun() {
        uninstallFromNonPersonalUsers()
        execOperations.exec {
            commandLine("adb", "install", "--user", PERSONAL_USER_ID, "-r", apkFile.get().asFile.absolutePath)
        }
        execOperations.exec {
            commandLine(
                "adb", "shell", "am", "start", "--user", PERSONAL_USER_ID,
                "-n", "${applicationId.get()}/app.typelauncher.MainActivity",
            )
        }
    }

    private fun uninstallFromNonPersonalUsers() {
        val usersOutput = ByteArrayOutputStream()
        execOperations.exec {
            commandLine("adb", "shell", "pm", "list", "users")
            standardOutput = usersOutput
        }

        USER_INFO_REGEX.findAll(usersOutput.toString())
            .map { match -> match.groupValues[1] }
            .filter { userId -> userId != PERSONAL_USER_ID }
            .forEach { userId ->
                execOperations.exec {
                    commandLine("adb", "shell", "pm", "uninstall", "--user", userId, applicationId.get())
                    isIgnoreExitValue = true
                }
            }
    }

    private companion object {
        const val PERSONAL_USER_ID = "0"
        val USER_INFO_REGEX = Regex("""UserInfo\{(\d+):""")
    }
}

android {
    namespace = "app.typelauncher"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "app.typelauncher"
        minSdk = 34
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = "$baseVersionName.$gitCommitCount+$gitShortSha"
        buildConfigField("String", "LOCAL_BUILD_BRANCH", buildConfigString(localBuildBranch))
        buildConfigField("String", "LOCAL_BUILD_SHA", buildConfigString(localBuildSha))
        buildConfigField("boolean", "LOCAL_BUILD_DIRTY", localBuildDirty.toString())
        buildConfigField("long", "LOCAL_BUILD_TIME_MILLIS", "${localBuildTimeMillis}L")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // CI materializes a stable debug keystore from a secret and points
        // DEBUG_KEYSTORE_FILE at it, so successive Firebase App Distribution
        // builds carry the same signature and tester devices can install
        // them as updates. Local builds without DEBUG_KEYSTORE_FILE set
        // fall through to AGP's auto-generated ~/.android/debug.keystore.
        // See docs/firebase-app-distribution.md.
        getByName("debug") {
            val keystorePath = providers.environmentVariable("DEBUG_KEYSTORE_FILE").orNull
            if (!keystorePath.isNullOrEmpty() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("DEBUG_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("DEBUG_KEY_ALIAS").getOrElse("androiddebugkey")
                keyPassword = providers.environmentVariable("DEBUG_KEY_PASSWORD").orNull
            }
        }
        // CI materializes a release keystore from a secret for the Play Store
        // internal-track upload (see docs/play-store-internal-track.md). The
        // keystore is the upload key; Play App Signing re-signs with its
        // managed app-signing key before delivery to devices. Local builds
        // without RELEASE_KEYSTORE_FILE set produce an unsigned release AAB,
        // which is fine for inspection and means forks build cleanly.
        create("release") {
            val keystorePath = providers.environmentVariable("RELEASE_KEYSTORE_FILE").orNull
            if (!keystorePath.isNullOrEmpty() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = debugApplicationIdSuffix
            manifestPlaceholders["appLabel"] = debugAppLabel
            manifestPlaceholders["launcherIcon"] = debugLauncherIcon
            manifestPlaceholders["launcherRoundIcon"] = debugLauncherRoundIcon
            buildConfigField("String", "SEARCH_PLACEHOLDER_SUFFIX", buildConfigString(debugSearchPlaceholderSuffix))
            buildConfigField("boolean", "PLAY_UPDATE_CHECKS_ENABLED", "false")
            // CI runs R8 in shrink-only mode (see proguard-rules.pro) so tester APKs
            // drop the bulk of unused code, including the unreferenced 99% of
            // material-icons-extended. Local debug builds skip R8 to keep the
            // edit-install loop fast.
            isMinifyEnabled = isCiBuild
            isShrinkResources = isCiBuild
            // AGP 9.x rejects the non-optimize baseline (proguard-android.txt) by
            // default, so we always pull in the optimize baseline and rely on
            // -dontoptimize in proguard-rules.pro to keep this a shrink-only run.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = isCiBuild
            isShrinkResources = isCiBuild
            manifestPlaceholders["appLabel"] = releaseAppLabel
            manifestPlaceholders["launcherIcon"] = releaseLauncherIcon
            manifestPlaceholders["launcherRoundIcon"] = releaseLauncherRoundIcon
            buildConfigField("String", "SEARCH_PLACEHOLDER_SUFFIX", buildConfigString(""))
            buildConfigField("boolean", "PLAY_UPDATE_CHECKS_ENABLED", "true")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only attach the release signingConfig when CI has populated it;
            // otherwise an unset storeFile makes bundleRelease fail locally
            // for anyone without the secrets.
            if (!providers.environmentVariable("RELEASE_KEYSTORE_FILE").orNull.isNullOrEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

tasks.register<InstallAndRunPersonalDebugTask>("installAndRun") {
    group = "install"
    description = "Installs the debug APK for the personal profile (user 0) only, then launches it."
    dependsOn("assembleDebug")
    apkFile.set(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    applicationId.set(debugApplicationId)
}

tasks.withType<Test>().configureEach {
    if (project.hasProperty("roborazzi.test.record")) {
        jvmArgs("-Droborazzi.test.record=true")
    }
    if (project.hasProperty("roborazzi.test.verify")) {
        jvmArgs("-Droborazzi.test.verify=true")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(platform(libs.androidx.compose.bom))
    testImplementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.material)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.perf)
    implementation(libs.play.app.update)
    // Pure-Java SVG renderer (Maven Central, ASL 2.0). Used by `AppIconLoader`
    // to rasterise user-supplied SVG icon overrides at the requested target
    // size. Raster overrides (PNG/JPEG/WEBP) go through Android's
    // `BitmapFactory` instead and don't need this dependency.
    implementation(libs.androidsvg)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.uiautomator)
}