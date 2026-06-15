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

/**
 * Coverage for [searchInlineSuggestion], which decides whether (and what) the
 * inline in-field autocomplete suggestion shows. It works in every layout, so
 * there's no icon-only gate — but it must stay hidden when the query is blank or
 * there's no match, and stays shown (fully bold) when the query already spells
 * the full name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchInlineSuggestionTest {
    private val calculator = installedApp("Calculator", "app.calculator")

    @Test
    fun showsTopMatchWithBoldedTypedLetters() {
        assertEquals(
            InlineSearchSuggestion(name = "Calculator", boldIndices = listOf(0, 1)),
            searchInlineSuggestion(query = "ca", topMatch = calculator),
        )
    }

    @Test
    fun packageOnlyMatchHasNoBoldIndices() {
        val virginMoney = installedApp("Credit Card", "com.virginmoney.cards")
        assertEquals(
            InlineSearchSuggestion(name = "Credit Card", boldIndices = emptyList()),
            searchInlineSuggestion(query = "virgin", topMatch = virginMoney),
        )
    }

    @Test
    fun hiddenWhenQueryBlank() {
        assertNull(searchInlineSuggestion(query = "   ", topMatch = calculator))
    }

    @Test
    fun hiddenWhenNoMatch() {
        assertNull(searchInlineSuggestion(query = "ca", topMatch = null))
    }

    @Test
    fun fullNameMatchStaysShownFullyBold() {
        // Typing the whole name keeps the suggestion visible with every letter
        // bold, so the field keeps confirming the match and its canonical casing
        // (e.g. typing "up" while "Up" is the match).
        val up = installedApp("Up", "app.up")
        assertEquals(
            InlineSearchSuggestion(name = "Up", boldIndices = listOf(0, 1)),
            searchInlineSuggestion(query ="up", topMatch = up),
        )
        assertEquals(
            InlineSearchSuggestion(name = "Up", boldIndices = listOf(0, 1)),
            searchInlineSuggestion(query ="Up", topMatch = up),
        )
        assertEquals(
            InlineSearchSuggestion(name = "Calculator", boldIndices = (0..9).toList()),
            searchInlineSuggestion(query ="Calculator", topMatch = calculator),
        )
    }

    private fun installedApp(name: String, packageName: String): InstalledApp {
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
