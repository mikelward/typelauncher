package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Folders for apps placed without the AI: by package, by name, and by guess. */
class KnownAppsTest {
    @Test
    fun wellKnownApps_goByPackage() {
        // The maintainer, 2026-11-19: Netflix was wrong; handle the common ones.
        assertEquals(AppCategory.Media, knownAppCategory("com.netflix.mediaclient"))
        assertEquals(AppCategory.Social, knownAppCategory("com.whatsapp"))
        assertEquals(AppCategory.Transport, knownAppCategory("com.waze"))
        assertEquals(AppCategory.Health, knownAppCategory("com.sec.android.app.shealth"))
        assertEquals(AppCategory.Internet, knownAppCategory("com.sec.android.app.sbrowser"))
        assertEquals(AppCategory.Photos, knownAppCategory("com.google.android.apps.photos"))
    }

    @Test
    fun gamesPublishers_goByPrefix() {
        assertEquals(AppCategory.Games, knownAppCategory("com.netflix.NGP.RedDeadRedemption"))
        assertEquals(AppCategory.Games, knownAppCategory("com.supercell.clashofclans"))
        assertEquals(AppCategory.Media, knownAppCategory("com.netflix.mediaclient"))
    }

    @Test
    fun aNameThatSaysWhatTheAppIs_placesIt() {
        // The maintainer, 2026-11-19: Samsung Health, Samsung Internet, and
        // anything called Gallery, Photos, Camera, News or Messages.
        assertEquals(AppCategory.Health, knownAppCategory("com.example.a", "Samsung Health"))
        assertEquals(AppCategory.Health, knownAppCategory("com.example.a2", "PureGym"))
        assertEquals(AppCategory.Health, knownAppCategory("com.example.a3", "The Gym Group"))
        assertEquals(AppCategory.Internet, knownAppCategory("com.example.b", "Samsung Internet"))
        assertEquals(AppCategory.Internet, knownAppCategory("com.example.c", "Mint Browser"))
        assertEquals(AppCategory.Photos, knownAppCategory("com.example.d", "Gallery"))
        assertEquals(AppCategory.Photos, knownAppCategory("com.example.e", "Camera"))
        assertEquals(AppCategory.News, knownAppCategory("com.example.f", "Sky News"))
        assertEquals(AppCategory.Social, knownAppCategory("com.example.g", "Messages"))
        assertEquals(AppCategory.Health, knownAppCategory("com.example.i", "MyFitnessPal"))
        assertEquals(AppCategory.Health, knownAppCategory("com.example.k", "Peloton"))
        assertEquals(AppCategory.Transport, knownAppCategory("com.example.l", "OsmAnd Maps"))
        assertEquals(AppCategory.Music, knownAppCategory("com.example.m", "Apple Music"))
        // The maintainer, 2026-11-19: a Phone folder, for anything named phone,
        // mobile or telecom; a more specific word still wins.
        assertEquals(AppCategory.Phone, knownAppCategory("com.example.q", "Phone"))
        assertEquals(AppCategory.Phone, knownAppCategory("com.example.r", "My Vodafone Mobile"))
        assertEquals(AppCategory.Phone, knownAppCategory("com.example.s", "BT Telecom"))
        assertEquals(AppCategory.Finance, knownAppCategory("com.example.t", "Barclays Mobile Banking"))
        assertEquals(AppCategory.Phone, knownAppCategory("com.google.android.dialer"))
        assertEquals(AppCategory.Phone, knownAppCategory("com.tmobile.pr.mytmobile"))
        // The maintainer, 2026-11-19: airlines are Travel, not Transport.
        assertEquals(AppCategory.Travel, knownAppCategory("com.example.n", "Virgin Atlantic Airways"))
        assertEquals(AppCategory.Travel, knownAppCategory("com.example.o", "Qantas Airlines"))
        for (airline in listOf("Fly Delta", "British Airways", "Ryanair", "JetBlue", "Jetstar", "United Airlines")) {
            assertEquals(airline, AppCategory.Travel, knownAppCategory("com.example.p", airline))
        }
        assertEquals(AppCategory.News, knownAppCategory("com.example.j", "SkyNews"))
        // A short word must end a word, not start one.
        assertNull(knownAppCategory("com.example.h", "Newsom for Governor"))
    }

    @Test
    fun aGuess_comesFromPackageAndNameWords() {
        assertEquals(AppCategory.Finance, guessedAppCategory("com.example.mobilebanking", "Acme"))
        assertEquals(AppCategory.Finance, guessedAppCategory("uk.co.acme.banking", "Acme"))
        assertEquals(AppCategory.Government, guessedAppCategory("uk.gov.example", "Check"))
        assertEquals(AppCategory.Games, guessedAppCategory("com.example.app", "Solitaire Classic"))
        assertEquals(AppCategory.Weather, guessedAppCategory("com.example.weatherpro", "Sky"))
        assertNull(guessedAppCategory("com.example.app", "Acme"))
    }
}
