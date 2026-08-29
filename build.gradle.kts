// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    // Applied by :app. Declared here so its version rides `kotlin` in the
    // catalog: the Compose compiler plugin must match the Kotlin version
    // exactly, and pinning it separately let a Kotlin bump leave it behind.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    // Exports the dependency graph as JSON for the Licenses page. Applied only
    // in :app; declared here so the version is shared like every other plugin.
    alias(libs.plugins.aboutlibraries) apply false
}