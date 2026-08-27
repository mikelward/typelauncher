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
    id("org.jetbrains.kotlin.plugin.compose")
    alias(libs.plugins.aboutlibraries)
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
// beside a CI-built one instead of fighting it for one package name. Every debug
// build is signed by whichever machine produced it, so sharing an ID isn't an
// upgrade, it's an INSTALL_FAILED_UPDATE_INCOMPATIBLE that forces an uninstall
// to switch between them.
// One suffix everywhere. It used to be `.debug` in CI and `.dev` locally, so a
// developer's debug build could co-install beside a CI-built one — but CI has
// built no debug APK since the build job moved to the release variant, so there
// is nothing left to sit beside. The other thing `.dev` did was keep local
// builds out of Firebase by not being a registered client; that is now a
// property of the debug variant itself (see above), not of its name.
val debugApplicationIdSuffix = ".debug"
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
    // The debug variant never gets Firebase. Not conditionally, not in CI —
    // never. A build that isn't the one users install has no business filing
    // crashes or analytics into the shared project beside the released build's,
    // and the environment it was built in doesn't change that.
    //
    // This used to be conditional, and both halves of the condition were wrong.
    // It keyed on whether the debug applicationId happened to be registered in
    // google-services.json, so dormancy was an accident of what a developer had
    // not registered rather than a property of the build — register it and
    // telemetry silently switched on. And it exempted CI, so the debug variant
    // there did wire Firebase up, while CI is precisely where the debug variant
    // is exercised most: testDebugUnitTest runs the unit and Robolectric
    // screenshot suites against it, with the generated google_app_id resource
    // present and FirebaseInitProvider in the merged manifest. Test runs
    // emitting analytics is not a risk worth carrying for a build nobody ships.
    //
    // What the CI exemption bought was a stale-secret signal: a missing debug
    // client made the plugin hard-fail, telling you GOOGLE_SERVICES_JSON needed
    // refreshing. It was guarding the wrong door — the client that matters is
    // the release one, which is what ships and what the mapping upload needs,
    // and the hasReleaseClient block below is where that is checked.
    run {
        // Disabling the task stops it regenerating, but Gradle does not delete a
        // disabled task's earlier output. Any checkout that has ever built this
        // variant with the plugin enabled still holds its google_app_id under
        // build/generated/res/, and the resource merge will happily package it
        // into the debug APK — Firebase would then initialize in a build this
        // guard exists to keep quiet. So purge that directory ahead of the
        // merge, not just skip the regeneration. (Two paths: AGP names the
        // directory after the task; older versions used
        // google-services/<variant>.)
        val purgeForeignFirebaseResources = tasks.register<Delete>("purgeDebugGoogleServicesResources") {
            description = "Deletes Firebase resources generated for the debug variant, which ships without them."
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
    // Only the deploy lane can ship, and only a shipping build's mapping file is
    // worth uploading. The build job runs assembleRelease as its R8 check (see
    // .github/workflows/ci.yml) and the Crashlytics plugin wires the upload into
    // the assemble task itself, so on a push to main that job would otherwise
    // upload a mapping for an APK nobody receives — duplicating, and racing, the
    // one `deploy` uploads for the build that actually ships. (On a PR the
    // question doesn't arise: google-services.json is materialized on push only,
    // so the plugin never applies there.) RELEASE_KEYSTORE_FILE is the deploy
    // job's own marker: it populates the signing config above, and no other job
    // sets it.
    //
    // Currently this changes nothing, because the CI google-services.json has no
    // release client and the hasReleaseClient block below already disables the
    // upload in every lane — main's deploy job included, where the log reads
    // `uploadCrashlyticsMappingFileRelease SKIPPED`. So no mapping reaches
    // Crashlytics for the shipping build today, and with R8 now obfuscating,
    // any crash it did receive would be unreadable. That is a Firebase console
    // question, not a build one. This gate is what keeps the fix to it from
    // introducing the duplicate upload: add the release client and the block
    // below stops disabling anything, at which point this becomes load-bearing.
    val canShip = !providers.environmentVariable("RELEASE_KEYSTORE_FILE").orNull.isNullOrEmpty()
    if (!canShip) {
        afterEvaluate {
            tasks.matching { it.name == "uploadCrashlyticsMappingFileRelease" }
                .configureEach { enabled = false }
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
// plain; the CI debug build wears a "DEBUG" bar; any build outside CI wears the
// "DEV" bar. Both bars use the same yellow plate and dark lettering — only the
// word differs. Previously the CI debug build was plain too, so it was
// indistinguishable from Play on the home screen, which is the one pairing most
// likely to be installed together.
val devLauncherIcon = "@mipmap/ic_launcher_local"
val devLauncherRoundIcon = "@mipmap/ic_launcher_round_local"
val releaseLauncherIcon = if (isCiBuild) "@mipmap/ic_launcher" else devLauncherIcon
val releaseLauncherRoundIcon = if (isCiBuild) "@mipmap/ic_launcher_round" else devLauncherRoundIcon
val debugLauncherIcon = if (isCiBuild) "@mipmap/ic_launcher_debug" else devLauncherIcon
val debugLauncherRoundIcon = if (isCiBuild) "@mipmap/ic_launcher_round_debug" else devLauncherRoundIcon

// The DEV badge on the local icon, said again in the name beside it — the badge
// is easy to miss at icon size, and the home-role picker and app list are text.
// Three builds can co-exist on one phone: the Play build (app.typelauncher), a
// CI-built debug APK (app.typelauncher.debug), and a local one. Only the Play
// build keeps the localized @string/app_name; the CI debug build is "Type
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
        version = release(37) {
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
        // Debug builds use AGP's auto-generated ~/.android/debug.keystore
        // everywhere, CI included. The stable keystore CI used to materialize
        // from a secret existed so App Distribution testers installed each new
        // build over the last one instead of hitting a signature mismatch;
        // nothing distributes a debug APK now.
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
            // No R8. AGP disables every optimization and all obfuscation for a
            // debuggable build type whatever isMinifyEnabled says, so this could
            // only ever have run the shrinker — never a preview of the release
            // build. Use assembleRelease to smoke-test the optimizer.
        }
        release {
            // Always, not just in CI. A release build is the artifact that
            // ships, so it should be the artifact anyone can reproduce: with
            // this gated on CI, a locally-built release APK was un-obfuscated
            // and un-shrunk, which is precisely the difference R8 bugs live in
            // (reflection, serialization, enum names). Testing one told you
            // nothing about the one Play serves. Debug is where the fast loop
            // lives; release is where the truth does.
            isMinifyEnabled = true
            isShrinkResources = true
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

// ----------------------------------------------------------------------------
// Open-source attribution -> committed res/raw/aboutlibraries.json
// ----------------------------------------------------------------------------
// AboutLibraries' Android auto-integration needs the legacy AppExtension that
// AGP 9 removed, so the plugin can't generate res/raw for us at build time.
// Instead we commit the export as a resource and regenerate it on demand with
// `./gradlew :app:exportBundledLicenses`; CI reruns it and fails on drift. The
// Licenses page reads the committed R.raw.aboutlibraries at runtime.
aboutLibraries {
    collect {
        // Scope the collection to the release variant so test/debug-only
        // artifacts (JUnit, Robolectric, Roborazzi, Compose tooling) never
        // reach the export; includePlatform = false drops BOM/platform POMs
        // (Compose, Firebase) that ship no runtime artifact.
        filterVariants.add("release")
        includePlatform = false
    }
    export {
        outputFile = file("src/main/res/raw/aboutlibraries.json")
        prettyPrint = true
        // Drop the full SPDX license text: it's resolved from a network-fetched
        // SPDX list whose exact wording varies by environment, so committing it
        // would make the regenerate-and-diff CI check non-deterministic. The
        // page still shows each license's name, SPDX id, and URL.
        excludeFields.add("License.content")
    }
}

// The plugin walks the dependency *graph*, so its export still lists nodes that
// resolve to no bundled artifact: Kotlin-Multiplatform metadata coordinates
// (e.g. androidx.compose.ui:ui, which selects ...:ui-android) and the
// org.jetbrains.compose redirect modules that alias to the androidx artifacts on
// Android. Both would render as duplicate rows. This task regenerates the export
// and then keeps only the coordinates that resolve to an actual artifact on the
// release runtime classpath -- i.e. what's really bundled in the APK.
@Suppress("UNCHECKED_CAST")
tasks.register("exportBundledLicenses") {
    description = "Exports open-source attributions filtered to the release APK's bundled artifacts."
    group = "build"
    dependsOn("exportLibraryDefinitions")
    val licensesFile = file("src/main/res/raw/aboutlibraries.json")
    val runtimeClasspath = configurations.named("releaseRuntimeClasspath")
    doLast {
        val bundled = runtimeClasspath.get().incoming
            .artifactView { lenient(true) }.artifacts.artifacts
            .mapNotNull { it.id.componentIdentifier as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            .map { "${it.moduleIdentifier.group}:${it.moduleIdentifier.name}" }
            .toSet()
        val root = groovy.json.JsonSlurper().parse(licensesFile) as MutableMap<String, Any?>
        val libraries = root["libraries"] as List<Map<String, Any?>>
        val kept = libraries.filter { (it["uniqueId"] as String) in bundled }
        root["libraries"] = kept
        // Prune any license no longer referenced by a kept library.
        val used = kept.flatMap { (it["licenses"] as? List<String>).orEmpty() }.toSet()
        (root["licenses"] as? MutableMap<String, Any?>)?.keys?.retainAll(used)
        licensesFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(root)) + "\n")
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
    // Reads the committed res/raw/aboutlibraries.json for the Licenses page.
    // Only `rememberLibraries` and the `Libs`/`Library` model are used -- the
    // artifact's own list UI is not.
    implementation(libs.aboutlibraries.compose.m3)
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