package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppDisambiguationTest {

    @Test
    fun brandKeyStripsCommonEtldPrefixes() {
        assertEquals("americanexpress", brandKey("com.americanexpress.android.acctsvcs.us"))
        assertEquals("chase", brandKey("com.chase.sig.android"))
        assertEquals("fdroid", brandKey("org.fdroid.fdroid"))
        assertEquals("dwd", brandKey("de.dwd.warnapp"))
        assertEquals("typelauncher", brandKey("app.typelauncher.fake0"))
    }

    @Test
    fun brandKeyHandlesMultiComponentEtlds() {
        assertEquals("bigbank", brandKey("co.uk.bigbank.app"))
        assertEquals("acme", brandKey("com.au.acme.client"))
    }

    @Test
    fun brandKeyFallsBackToFirstComponentForUnknownEtld() {
        assertEquals("xyzzy", brandKey("xyzzy.something.weird"))
    }

    @Test
    fun brandKeyReturnsNullForEmptyInput() {
        assertNull(brandKey(""))
    }

    @Test
    fun amexThreePackUsesCountryCodeSuffix() {
        val apps = listOf(
            personalApp("Amex", "com.americanexpress.android.acctsvcs.us"),
            personalApp("Amex UK", "com.americanexpress.android.acctsvcs.uk"),
            personalApp("Amex AU", "com.americanexpress.android.acctsvcs.au"),
        )
        val disambig = computeDisambiguators(apps).mapKeys { (id, _) ->
            apps.single { it.id == id }.name
        }
        assertEquals(mapOf("Amex" to "US", "Amex UK" to "UK", "Amex AU" to "AU"), disambig)
    }

    @Test
    fun chaseSameNamePairOnlyCountryCodeMemberGetsBadge() {
        val apps = listOf(
            personalApp("Chase", "com.chase.sig.android"),
            personalApp("Chase", "com.chase.uk.consumer"),
        )
        val disambig = computeDisambiguators(apps)
        // Only the UK tail contains a country code; "sig" is not a country
        // code so the sig variant gets no badge.
        assertEquals(1, disambig.size)
        assertEquals("UK", disambig[apps[1].id])
    }

    @Test
    fun googleFamilyIsNotAmbiguous() {
        // Google / Google Cloud / Google TV all share brand `google` and the
        // first word `Google`, but the suffixes "Cloud" / "TV" are not country
        // codes, so the group is rejected and no badges are rendered.
        val apps = listOf(
            personalApp("Google", "com.google.android.googlequicksearchbox"),
            personalApp("Google Cloud", "com.google.android.apps.cloudconsole"),
            personalApp("Google TV", "com.google.android.videos"),
        )
        assertEquals(emptyMap<String, String>(), computeDisambiguators(apps))
    }

    @Test
    fun youTubeFamilyIsNotAmbiguous() {
        val apps = listOf(
            personalApp("YouTube", "com.google.android.youtube"),
            personalApp("YouTube Music", "com.google.android.apps.youtube.music"),
            personalApp("YouTube Kids", "com.google.android.apps.youtube.kids"),
        )
        assertEquals(emptyMap<String, String>(), computeDisambiguators(apps))
    }

    @Test
    fun unrelatedAppsAreNotGrouped() {
        val apps = listOf(
            personalApp("Browser", "com.android.browser"),
            personalApp("Calculator", "com.android.calculator2"),
            personalApp("Clock", "com.android.deskclock"),
        )
        // Same brand `android` but completely different first words.
        assertEquals(emptyMap<String, String>(), computeDisambiguators(apps))
    }

    @Test
    fun parenthesisedCountrySuffixCounts() {
        val apps = listOf(
            personalApp("Bank (US)", "com.bigbank.us"),
            personalApp("Bank (UK)", "com.bigbank.uk"),
        )
        val disambig = computeDisambiguators(apps)
        assertEquals(2, disambig.size)
        assertEquals("US", disambig[apps[0].id])
        assertEquals("UK", disambig[apps[1].id])
    }

    @Test
    fun singletonAppGetsNoBadge() {
        val apps = listOf(personalApp("Solo", "com.example.solo"))
        assertEquals(emptyMap<String, String>(), computeDisambiguators(apps))
    }

    @Test
    fun displayNameAppendsSuffixWhenAbsentFromName() {
        val app = personalApp("Chase", "com.chase.sig.android").copy(disambiguator = "US")
        assertEquals("Chase (US)", app.displayName)
    }

    @Test
    fun displayNameSuppressesRedundantSuffix() {
        // "Amex UK" already contains "UK" as a token, so a "UK" disambiguator
        // shouldn't produce the redundant "Amex UK (UK)".
        val app = personalApp("Amex UK", "com.americanexpress.android.acctsvcs.uk")
            .copy(disambiguator = "UK")
        assertEquals("Amex UK", app.displayName)
    }

    @Test
    fun displayNameStripsParensWhenCheckingForRedundantSuffix() {
        // A label that already includes a parenthesised country marker
        // ("Bank (US)") shouldn't render as "Bank (US) (US)" — the token
        // comparison normalises surrounding punctuation before checking.
        val app = personalApp("Bank (US)", "com.bigbank.us")
            .copy(disambiguator = "US")
        assertEquals("Bank (US)", app.displayName)
    }

    @Test
    fun samePackagePersonalWorkPairIsNotDisambiguated() {
        // Personal Gmail and a work-profile clone of the same Gmail share
        // the same package — the work-profile badge already disambiguates
        // them and the package-tail picker would just produce identical
        // labels for both, so we skip the group entirely.
        val personal = personalApp("Gmail", "com.google.android.gm")
        val work = personalApp("Gmail", "com.google.android.gm")
            // Different `id` because UserHandle differs. We can't easily
            // construct a foreign UserHandle in a unit test, so simulate
            // the ID divergence via a different launchIntent component.
            .let { it.copy(launchIntent = Intent.makeMainActivity(ComponentName(it.packageName, "${it.packageName}.WorkActivity"))) }
        assertEquals(emptyMap<String, String>(), computeDisambiguators(listOf(personal, work)))
    }

    @Test
    fun nonCountryCodePackageTailsProduceNoBadge() {
        // "sig" and "intl" are not country codes; neither variant gets a badge.
        val apps = listOf(
            personalApp("Chase", "com.chase.sig.android"),
            personalApp("Chase", "com.chase.intl.android"),
        )
        assertEquals(emptyMap<String, String>(), computeDisambiguators(apps))
    }

    @Test
    fun brandKeyHandlesKonyAndIeEtldPrefixes() {
        assertEquals("capitalone", brandKey("com.konylabs.capitalone"))
        assertEquals("capitalone", brandKey("com.ie.capitalone.uk"))
    }

    @Test
    fun chaseKonyUsAndIeUkOnlyUkVariantGetsBadge() {
        // Both packages resolve to brand "capitalone" after stripping their
        // respective eTLD prefixes, grouping them together. The IE/UK package
        // tail ends in "uk" so it receives the UK badge; the Kony package has
        // an empty tail after the brand so it gets no badge.
        val apps = listOf(
            personalApp("Chase", "com.konylabs.capitalone"),
            personalApp("Chase", "com.ie.capitalone.uk"),
        )
        val disambig = computeDisambiguators(apps)
        assertEquals(1, disambig.size)
        assertEquals("UK", disambig[apps[1].id])
    }

    @Test
    fun capitalOneSameNameTwoWordPairOnlyUkVariantGetsBadge() {
        // Real-world case: both apps ship with the literal display name
        // "Capital One", grouped under brand "capitalone" via the
        // com.ie / com.konylabs eTLD prefixes. The two-word name means the
        // suffix is "One" rather than empty, but the suffixes are identical
        // for both apps so the user can't tell them apart — the IE/UK
        // package tail ending in "uk" gets the UK badge, the Kony tail gets
        // no badge.
        val apps = listOf(
            personalApp("Capital One", "com.konylabs.capitalone"),
            personalApp("Capital One", "com.ie.capitalone.uk"),
        )
        val disambig = computeDisambiguators(apps)
        assertEquals(1, disambig.size)
        assertEquals("UK", disambig[apps[1].id])
    }

    @Test
    fun differingNonCountrySuffixesStillDoNotProduceBadges() {
        // "Capital One Premium" vs "Capital One Business" share brand and
        // first word but the suffixes differ and neither is a country code,
        // so the group is rejected — the user can already tell them apart
        // from the display name alone.
        val apps = listOf(
            personalApp("Capital One Premium", "com.konylabs.capitalone"),
            personalApp("Capital One Business", "com.ie.capitalone.uk"),
        )
        assertEquals(emptyMap<String, String>(), computeDisambiguators(apps))
    }

    @Test
    fun nonIsoRegionalMarkersAreNotMatched() {
        // We deliberately don't include "EMEA" / "APAC" / "ANZ" / "ROW" in
        // the regional-marker list — keeping the set to ISO 3166-1 alpha-2
        // (plus the colloquial "uk") avoids false grouping like ANZ-the-bank
        // being mis-classified as a regional variant. Re-add specific
        // markers if real apps in the wild are observed using them.
        val apps = listOf(
            personalApp("Acme EMEA", "com.acme.emea"),
            personalApp("Acme APAC", "com.acme.apac"),
        )
        assertEquals(emptyMap<String, String>(), computeDisambiguators(apps))
    }

    @Test
    fun onlyMembersWithCountryCodeTailGetBadge() {
        // "app" and "premium" are not country codes, so only the "uk" tail
        // gets a badge; the other group members are left unbadged.
        val apps = listOf(
            personalApp("Bank", "com.bigbank.app"),
            personalApp("Bank UK", "com.bigbank.uk"),
            personalApp("Bank Premium", "com.bigbank.premium"),
        )
        val disambig = computeDisambiguators(apps)
        assertEquals(1, disambig.size)
        assertEquals("UK", disambig[apps[1].id])
    }

    @Test
    fun gbAndUkBothAccepted() {
        // Some apps use the colloquial "uk" suffix, others use the ISO "gb"
        // — both should be recognised so that an app pair using one or the
        // other still gets disambiguated.
        val gb = listOf(
            personalApp("Acme", "com.acme.us"),
            personalApp("Acme GB", "com.acme.gb"),
        )
        val gbResult = computeDisambiguators(gb)
        assertEquals("US", gbResult[gb[0].id])
        assertEquals("GB", gbResult[gb[1].id])

        val uk = listOf(
            personalApp("Acme", "com.acme.us"),
            personalApp("Acme UK", "com.acme.uk"),
        )
        val ukResult = computeDisambiguators(uk)
        assertEquals("US", ukResult[uk[0].id])
        assertEquals("UK", ukResult[uk[1].id])
    }

    @Test
    fun tvAndCdSuffixesAreNotRegional() {
        // Deliberate exclusions to avoid confusing television / compact-disc
        // with Tuvalu / DR Congo.
        val tv = listOf(
            personalApp("Acme", "com.acme.app"),
            personalApp("Acme TV", "com.acme.tv"),
        )
        assertEquals(emptyMap<String, String>(), computeDisambiguators(tv))
    }

    private fun personalApp(name: String, packageName: String): InstalledApp {
        val component = ComponentName(packageName, "$packageName.LaunchActivity")
        return InstalledApp(
            name = name,
            packageName = packageName,
            launchIntent = Intent.makeMainActivity(component),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = true,
        )
    }
}
