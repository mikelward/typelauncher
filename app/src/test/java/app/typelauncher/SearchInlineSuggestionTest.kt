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
 * inline in-field autocomplete suggestion shows. Unlike the icon-only drop-down
 * hint, it works in every layout, so there's no icon-only gate — but it must
 * stay hidden when the setting is off, the query is blank, there's no match, or
 * the query already spells the full name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchInlineSuggestionTest {
    private val calculator = installedApp("Calculator", "app.calculator")

    @Test
    fun showsTopMatchWithBoldedTypedLetters() {
        assertEquals(
            InlineSearchSuggestion(name = "Calculator", boldIndices = listOf(0, 1)),
            searchInlineSuggestion(showSuggestion = true, query = "ca", topMatch = calculator),
        )
    }

    @Test
    fun packageOnlyMatchHasNoBoldIndices() {
        val virginMoney = installedApp("Credit Card", "com.virginmoney.cards")
        assertEquals(
            InlineSearchSuggestion(name = "Credit Card", boldIndices = emptyList()),
            searchInlineSuggestion(showSuggestion = true, query = "virgin", topMatch = virginMoney),
        )
    }

    @Test
    fun hiddenWhenSettingOff() {
        assertNull(searchInlineSuggestion(showSuggestion = false, query = "ca", topMatch = calculator))
    }

    @Test
    fun hiddenWhenQueryBlank() {
        assertNull(searchInlineSuggestion(showSuggestion = true, query = "   ", topMatch = calculator))
    }

    @Test
    fun hiddenWhenNoMatch() {
        assertNull(searchInlineSuggestion(showSuggestion = true, query = "ca", topMatch = null))
    }

    @Test
    fun hiddenWhenQueryEqualsFullName() {
        // Once the whole name is typed there's nothing left to suggest; echoing
        // it would just double the text in the field.
        assertNull(searchInlineSuggestion(showSuggestion = true, query = "Calculator", topMatch = calculator))
        assertNull(searchInlineSuggestion(showSuggestion = true, query = "calculator", topMatch = calculator))
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
