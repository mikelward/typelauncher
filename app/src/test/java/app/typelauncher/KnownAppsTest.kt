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
        // The maintainer, 2026-11-19: a Phone folder for the phone app and
        // carriers. Not anything named "phone" or "mobile": in Play's top
        // charts those were mostly banks, games and utilities.
        assertEquals(AppCategory.Phone, knownAppCategory("com.example.q", "Phone"))
        for (label in listOf("RBC Mobile", "PUBG MOBILE", "Clap to Find My Phone", "Duo Mobile")) {
            assertEquals(label, null, knownAppCategory("com.example.q2", label))
        }
        for (label in listOf("EE", "My Telstra", "My Optus", "T-Mobile", "giffgaff", "2degrees NZ")) {
            assertEquals(label, AppCategory.Phone, knownAppCategory("com.example.q3", label))
        }
        // The word "AI" is a feature, not a folder.
        for ((label, category) in listOf(
            "AI Photo Editor: Art Studio" to AppCategory.Design,
            "Claude by Anthropic" to AppCategory.Ai,
            "Grok Bot" to AppCategory.Ai,
            "BBC Sport - News & Live Scores" to AppCategory.Sports,
            "bet365 - Sportsbook & Casino" to AppCategory.Betting,
            "The Courier-Mail: QLD News" to AppCategory.News,
            "AlfredCamera Home Security app" to AppCategory.Home,
            "RingGo: Mobile Car Parking App" to AppCategory.Transport,
            "Flightradar24 Flight Tracker" to AppCategory.Travel,
            "TfL Go: Plan, Pay, Travel" to AppCategory.Transport,
            // Codex on PR #875: "sport" must start a word, not end one.
            "Public Transport Victoria app" to AppCategory.Transport,
            "Passport Renewal" to null,
        )) {
            assertEquals(label, category, knownAppCategory("com.example.q4", label))
        }
        assertEquals(AppCategory.Phone, knownAppCategory("com.example.r", "My Vodafone Mobile"))
        assertEquals(AppCategory.Phone, knownAppCategory("com.example.s", "BT Telecom"))
        assertEquals(AppCategory.Finance, knownAppCategory("com.example.t", "Barclays Mobile Banking"))
        assertEquals(AppCategory.Phone, knownAppCategory("com.google.android.dialer"))
        assertEquals(AppCategory.Phone, knownAppCategory("com.tmobile.pr.mytmobile"))
        // The maintainer, 2026-11-19: the classic handset, as dialer icons draw it.
        assertEquals(FolderIcon.Call, AppCategory.Phone.icon)
        // The maintainer, 2026-11-19: a Reading folder for books, audiobooks,
        // Kindle and Audible.
        assertEquals(AppCategory.Reading, knownAppCategory("com.amazon.kindle"))
        assertEquals(AppCategory.Reading, knownAppCategory("com.audible.application"))
        assertEquals(AppCategory.Reading, knownAppCategory("com.example.u", "BookBeat Audiobooks"))
        assertEquals(AppCategory.Reading, knownAppCategory("com.example.v", "Kindle Unlimited"))
        assertEquals(AppCategory.Reading, knownAppCategory("com.example.w", "Manga Plus"))
        // Codex on PR #866: "ebook" must start a word, not end Facebook.
        assertEquals(AppCategory.Reading, knownAppCategory("com.example.y", "eBook Reader"))
        assertNull(knownAppCategory("com.example.z", "Facebook Lite"))
        assertNull(knownAppCategory("com.example.z2", "Notebook"))
        assertEquals(null, guessedAppCategory("com.facebook.lite", "Lite"))
        // ...and library apps like Libby.
        assertEquals(AppCategory.Reading, knownAppCategory("com.overdrive.mobile.android.libby"))
        assertEquals(AppCategory.Reading, knownAppCategory("com.example.x", "Anytown Public Library"))
        // The maintainer, 2026-11-19: a Code folder by default, with GitHub.
        assertEquals(AppCategory.Code, knownAppCategory("com.github.android"))
        assertEquals(AppCategory.Code, knownAppCategory("com.example.c1", "Learn Coding"))
        assertEquals(AppCategory.Code, knownAppCategory("com.example.c2", "VS Code"))
        assertEquals(AppCategory.Code, knownAppCategory("com.example.c3", "Git Client"))
        assertEquals(AppCategory.Tools, knownAppCategory("com.example.c4", "QR Code Reader"))
        assertNull(knownAppCategory("com.example.c5", "Barcode Scanner"))
        assertNull(knownAppCategory("com.example.c6", "Legit Deals"))
        // The maintainer, 2026-11-19: an AI folder too.
        assertEquals(AppCategory.Ai, knownAppCategory("com.openai.chatgpt"))
        assertEquals(AppCategory.Ai, knownAppCategory("com.google.android.apps.bard"))
        assertEquals(AppCategory.Ai, knownAppCategory("com.example.ai1", "Meta AI"))
        assertNull(knownAppCategory("com.example.ai2", "Thai Food Near Me"))
        assertNull(knownAppCategory("com.example.ai3", "Visit Dubai"))
        // The maintainer, 2026-11-19: airlines are Travel, not Transport.
        assertEquals(AppCategory.Travel, knownAppCategory("com.example.n", "Virgin Atlantic Airways"))
        assertEquals(AppCategory.Travel, knownAppCategory("com.example.o", "Qantas Airlines"))
        for (airline in listOf("Fly Delta", "British Airways", "Ryanair", "JetBlue", "Jetstar", "United Airlines")) {
            assertEquals(airline, AppCategory.Travel, knownAppCategory("com.example.p", airline))
        }
        // The maintainer, 2026-11-19: apps that landed in Other.
        for ((label, category) in listOf(
            "UniFi" to AppCategory.Internet,
            "Wanderlog" to AppCategory.Travel,
            "Libraries" to AppCategory.Reading,
            "My HCA" to AppCategory.Health,
            "My Bupa" to AppCategory.Health,
            "Priority Pass" to AppCategory.Travel,
            "Patient Access" to AppCategory.Health,
            "Saily" to AppCategory.Phone,
            "Suzerain" to AppCategory.Games,
        )) {
            assertEquals(label, category, knownAppCategory("com.example.q", label))
        }
        // The maintainer, 2026-11-19: a Post & parcels folder.
        for (label in listOf("Australia Post", "DPD", "FedEx", "UPS", "Royal Mail", "Parcel Tracker", "CouriersPlease")) {
            assertEquals(label, AppCategory.Post, knownAppCategory("com.example.post", label))
        }
        assertEquals(AppCategory.Productivity, knownAppCategory("com.example.mail", "Proton Mail"))
        assertNull(knownAppCategory("com.example.wp", "The Washington Post"))
        // The maintainer, 2026-11-19: Mini Metro is a game, not Transport.
        assertEquals(AppCategory.Games, knownAppCategory("com.example.mm", "Mini Metro"))
        assertEquals(AppCategory.Games, knownAppCategory("nz.co.codepoint.minimetro"))
        assertEquals(AppCategory.Games, knownAppCategory("com.example.dd", "The Darkside Detective"))
        // The maintainer, 2026-11-19: a Security folder.
        assertEquals(AppCategory.Security, knownAppCategory("com.x8bit.bitwarden"))
        assertEquals(AppCategory.Security, knownAppCategory("com.google.android.apps.authenticator2"))
        for (label in listOf("1Password", "Microsoft Authenticator", "Norton 360", "Incogni", "Proton Pass", "Password Safe")) {
            assertEquals(label, AppCategory.Security, knownAppCategory("com.example.sec", label))
        }
        assertNull(knownAppCategory("com.example.af", "Aura Frames"))
        for ((label, category) in listOf(
            "TheFork" to AppCategory.Food,
            "OpenTable" to AppCategory.Food,
            "Pret" to AppCategory.Food,
            "Amazon Fire TV" to AppCategory.Media,
            "Antigravity" to AppCategory.Code,
            "PayByPhone" to AppCategory.Transport,
        )) {
            assertEquals(label, category, knownAppCategory("com.example.t", label))
        }
        assertNull(knownAppCategory("com.example.u", "Interpreter"))
        // The maintainer, 2026-11-19: Betting, Design & editing, Events &
        // tickets, Jobs & work, and Rewards & loyalty folders.
        for ((label, category) in listOf(
            "Sportsbet - Sports Betting App" to AppCategory.Betting,
            "DraftKings: Sports & Casino" to AppCategory.Betting,
            "MyLotto" to AppCategory.Betting,
            "CapCut - Video Editor" to AppCategory.Design,
            "Canva: AI Photo & Video Editor" to AppCategory.Design,
            "PixVerse: AI Video Generator" to AppCategory.Design,
            "Ticketmaster－Buy, Sell Tickets" to AppCategory.Events,
            "StubHub - Live Event Tickets" to AppCategory.Events,
            "SEEK: Jobs, Job Search & Work" to AppCategory.Jobs,
            "Indeed Job Search" to AppCategory.Jobs,
            "DoorDash - Dasher" to AppCategory.Jobs,
            "Flybuys" to AppCategory.Rewards,
            "Avion Rewards" to AppCategory.Rewards,
            "Upside: Gas & Food Cash Back" to null,
            "BBC Sport - News & Live Scores" to AppCategory.Sports,
        )) {
            assertEquals(label, category, knownAppCategory("com.example.nf", label))
        }
        assertEquals(AppCategory.Design, knownAppCategory("com.lemon.lvoverseas"))
        assertEquals(AppCategory.Photos, knownAppCategory("com.google.android.apps.photos"))
        // Brands are whole words.
        assertNull(knownAppCategory("com.example.r", "Unified Remote"))
        assertNull(knownAppCategory("com.example.s", "Priority Tasks"))
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
