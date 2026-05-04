plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
}

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
    // If google-services.json has no release client (app.typelauncher), disable the
    // release processing task so bundleRelease doesn't fail. When a release client is
    // present the task runs normally and Firebase/Crashlytics are wired up for production.
    val hasReleaseClient = firebaseConfigFile.readText().contains("\"app.typelauncher\"")
    if (!hasReleaseClient) {
        afterEvaluate {
            tasks.matching { it.name == "processReleaseGoogleServices" }.configureEach {
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
val isGitWorkingTreeDirty: Boolean =
    gitOutput("status", "--porcelain", fallback = "dirty").isNotEmpty()
val baseVersionName = "1.0"
val isCiBuild: Boolean = providers.environmentVariable("CI")
    .map { value -> value.equals("true", ignoreCase = true) }
    .getOrElse(false)
val launcherIconResource = if (isCiBuild) "@mipmap/ic_launcher" else "@mipmap/ic_launcher_local"
val launcherRoundIconResource = if (isCiBuild) "@mipmap/ic_launcher_round" else "@mipmap/ic_launcher_round_local"
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
        manifestPlaceholders["launcherIcon"] = launcherIconResource
        manifestPlaceholders["launcherRoundIcon"] = launcherRoundIconResource
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
            applicationIdSuffix = ".debug"
            buildConfigField("String", "SEARCH_PLACEHOLDER_SUFFIX", buildConfigString(debugSearchPlaceholderSuffix))
            buildConfigField("boolean", "PLAY_UPDATE_CHECKS_ENABLED", "false")
        }
        release {
            isMinifyEnabled = false
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
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

tasks.register<Exec>("installAndRun") {
    dependsOn("installDebug")
    commandLine("adb", "shell", "am", "start", "-n", "app.typelauncher.debug/.MainActivity")
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