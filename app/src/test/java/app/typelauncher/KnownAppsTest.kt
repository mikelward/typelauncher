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
        assertEquals(AppCategory.Car, knownAppCategory("com.waze"))
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
        assertEquals(AppCategory.Maps, knownAppCategory("com.example.l", "OsmAnd Maps"))
        assertEquals(AppCategory.Music, knownAppCategory("com.example.m", "Apple Music"))
        // The maintainer, 2026-11-19: a Phone folder for the phone app and
        // carriers. Not anything named "phone" or "mobile": in Play's top
        // charts those were mostly banks, games and utilities.
        assertEquals(AppCategory.Phone, knownAppCategory("com.example.q", "Phone"))
        for (label in listOf("PUBG MOBILE", "Clap to Find My Phone", "Duo Mobile")) {
            assertEquals(label, null, knownAppCategory("com.example.q2", label))
        }
        // "One NZ" is also Westpac's bank name there.
        assertEquals(AppCategory.Finance, knownAppCategory("com.example.q5", "Westpac One NZ Mobile Banking"))
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
            "RingGo: Mobile Car Parking App" to AppCategory.Car,
            "Flightradar24 Flight Tracker" to AppCategory.Travel,
            "TfL Go: Plan, Pay, Travel" to AppCategory.Transit,
            // Codex on PR #875: "sport" must start a word, not end one.
            "Public Transport Victoria app" to AppCategory.Transit,
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
        // Not Reading for its "ebook": a notebook is notes (maintainer, 2026-11-21).
        assertEquals(AppCategory.Notes, knownAppCategory("com.example.z2", "Notebook"))
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
            "Starlink" to AppCategory.Internet,
            "LoungeKey" to AppCategory.Travel,
            "9Now" to AppCategory.Media,
            "Toyota Connect" to AppCategory.Car,
            "Kia Connect" to AppCategory.Car,
            "Tesco Mobile" to AppCategory.Phone,
            "CommSec" to AppCategory.Finance,
            "The AA (NZ)" to AppCategory.Car,
            "RAC" to AppCategory.Car,
            "My NRMA" to AppCategory.Car,
            "Costa Coffee Club" to AppCategory.Food,
            "BWS" to AppCategory.Food,
            "Liquorland" to AppCategory.Food,
            "Dan Murphy's" to AppCategory.Food,
            "Wendy's" to AppCategory.Food,
            "Sainsbury's" to AppCategory.Food,
            "Klarna: Smarter everyday money" to AppCategory.Finance,
            "Bible Offline-KJV Holy Bible" to AppCategory.Reading,
            "Life360: Family Safety & GPS" to AppCategory.Kids,
            "Argos - Shop Tech" to AppCategory.Shopping,
            "YouTube TV: Live TV & more" to AppCategory.Media,
            "Hive" to AppCategory.Home,
            "Employment Hero Work" to AppCategory.Jobs,
            "Volkswagen Car-Net" to AppCategory.Car,
            "Miele Professional" to AppCategory.Home,
            "Three" to AppCategory.Phone,
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
        assertEquals(AppCategory.Office, knownAppCategory("com.example.mail", "Proton Mail"))
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
            "PayByPhone" to AppCategory.Car,
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
    fun categoryBatches_placeWholeCategories() {
        // Each batch covers its category, not one person's apps (maintainer, 2026-11-21).
        for ((label, category) in listOf(
            "TP-Link Tapo" to AppCategory.Home,
            "Kasa Smart" to AppCategory.Home,
            "Wyze - Make Your Home Smarter" to AppCategory.Home,
            "Philips Hue" to AppCategory.Home,
            "Smart Life - Smart Living" to AppCategory.Home,
            "BlackCloak" to AppCategory.Security,
            "ESET Mobile Security" to AppCategory.Security,
            "Lookout Life - Mobile Security" to AppCategory.Security,
            "Surfshark VPN" to AppCategory.Internet,
            "Mullvad VPN" to AppCategory.Internet,
            "Firefox Focus: No Fuss Browser" to AppCategory.Internet,
            "Ecosia" to AppCategory.Internet,
            "RingGo: Mobile Car Parking App" to AppCategory.Car,
            "JustPark" to AppCategory.Car,
            "ParkMobile" to AppCategory.Car,
            "LEGOLAND Windsor Resort" to AppCategory.Events,
            "Disneyland" to AppCategory.Events,
            "Universal Orlando Resort" to AppCategory.Events,
            "Six Flags" to AppCategory.Events,
            "Knott's Berry Farm" to AppCategory.Events,
            "SIXT rent. share. ride." to AppCategory.Car,
            "Enterprise Rent-A-Car" to AppCategory.Car,
            "Hertz Car Rental" to AppCategory.Car,
            "Turo - Car Rental Marketplace" to AppCategory.Car,
        )) {
            assertEquals(label, category, knownAppCategory("com.example.batch", label))
        }
        // The Transport split (maintainer, 2026-11-21).
        for ((label, category) in listOf(
            "Uber" to AppCategory.Car,
            "Uber Eats: Food Delivery" to AppCategory.Food,
            "Lyft" to AppCategory.Car,
            "Google Maps" to AppCategory.Maps,
            "Citymapper" to AppCategory.Maps,
            "Trainline: Cheap UK, EU Trains" to AppCategory.Transit,
            "Moovit: Bus & Rail Live Times" to AppCategory.Transit,
            "Santander Cycles" to AppCategory.Cycling,
            "Citi Bike" to AppCategory.Cycling,
            "Marriott Bonvoy" to AppCategory.Hotels,
            "Airbnb" to AppCategory.Hotels,
            "Vrbo Holiday Rentals" to AppCategory.Hotels,
            "Hotels.com: Travel Booking" to AppCategory.Hotels,
            "Skyscanner" to null,
        )) {
            assertEquals(label, category, knownAppCategory("com.example.split", label))
        }
        assertEquals(AppCategory.Car, knownAppCategory("com.ubercab"))
        assertEquals(AppCategory.Cycling, knownAppCategory("com.limebike"))
        assertEquals(AppCategory.Hotels, knownAppCategory("com.airbnb.android"))
        assertEquals(AppCategory.Maps, knownAppCategory("com.google.android.apps.maps"))
        // Ordinary words stay out.
        assertNull(knownAppCategory("com.example.ring", "Ring Fit Tracker"))
        assertNull(knownAppCategory("com.example.brave", "Brave Frontier"))
    }

    @Test
    fun theMaintainersAppsAndTheirNeighbors_arePlaced() {
        // The maintainer, 2026-11-21: the apps Google Play was asked about,
        // by name (as a sideloaded copy would have) and by package.
        for ((label, category) in listOf(
            "ClothesCast" to AppCategory.Weather,
            "Snoozemo" to AppCategory.Tools,
            "Simmo" to AppCategory.Phone,
            "readmo" to AppCategory.Reading,
            "StopDash" to AppCategory.Transit,
            "Google Cloud" to AppCategory.Code,
            "AWS Console" to AppCategory.Code,
            "Microsoft Azure" to AppCategory.Code,
            "Cloudflare" to AppCategory.Code,
            "Namecheap" to AppCategory.Code,
            "Tailscale" to AppCategory.Internet,
            "My Virgin Media" to AppCategory.Internet,
            "MyChart" to AppCategory.Health,
            "NHS App" to AppCategory.Health,
            "Better UK" to AppCategory.Health,
            "Music for Focus by Brain.fm" to AppCategory.Music,
            "Ampere" to AppCategory.Tools,
            "Anker Make" to AppCategory.Tools,
            "CareSuper" to AppCategory.Finance,
            "Chelsea FC" to AppCategory.Sports,
            "FC Barcelona" to AppCategory.Sports,
            "FC Bayern München" to AppCategory.Sports,
            "AFC Bournemouth" to AppCategory.Sports,
            "Clozemaster: language learning" to AppCategory.Learning,
            "Lightwave LinkPlus" to AppCategory.Home,
            "LNER | Train Tickets & Times" to AppCategory.Transit,
            "Merlin Bird ID by Cornell Lab" to AppCategory.Pets,
            "MyMelita" to AppCategory.Phone,
            "myCricket" to AppCategory.Phone,
            "Arro - Taxi App" to AppCategory.Car,
            "Abetta" to AppCategory.Car,
            "AddisonLee" to AppCategory.Car,
            "Tello Mobile" to AppCategory.Phone,
            "Talk Mobile" to AppCategory.Phone,
            "amaysim" to AppCategory.Phone,
            "Lebara SIM" to AppCategory.Phone,
            "SIM Manager" to AppCategory.Phone,
            "Raindrop.io" to AppCategory.Reading,
            "Virgin Australia" to AppCategory.Travel,
        )) {
            assertEquals(label, category, knownAppCategory("com.example.mine", label))
        }
        for ((packageName, category) in listOf(
            "app.clothescast" to AppCategory.Weather,
            "app.snoozemo" to AppCategory.Tools,
            "app.simmo" to AppCategory.Phone,
            "com.google.android.apps.cloudconsole" to AppCategory.Code,
            "com.ex3ndr.happy" to AppCategory.Code,
            "sh.paseo" to AppCategory.Code,
            "com.tailscale.ipn" to AppCategory.Internet,
            "com.cloudflare.cloudflareoneagent" to AppCategory.Internet,
            "com.sony.songpal.mdr" to AppCategory.Music,
            "au.com.stan.and" to AppCategory.Media,
        )) {
            assertEquals(packageName, category, knownAppCategory(packageName, "Happy"))
        }
        // Names the batch leaves to their packages.
        assertNull(knownAppCategory("com.example.stan", "Stan"))
        assertNull(knownAppCategory("com.example.paseo", "Paseo"))
        // "sim" and "fc" only as words of their own; bare "cricket" and "tello" aren't phone plans.
        assertNull(knownAppCategory("com.example.sims", "Simsy"))
        assertEquals(AppCategory.Sports, knownAppCategory("com.example.cricket", "Cricket Live Scores"))
        assertNull(knownAppCategory("com.example.drone", "Tello"))
        assertEquals(AppCategory.Phone, knownAppCategory("com.tello.ui", "My Tello"))
        assertEquals(AppCategory.Phone, knownAppCategory("com.tello.other", "Anything"))
        assertNull(knownAppCategory("com.example.fcm", "FCM Tester"))
        // Pedometers by package words alone.
        assertEquals(AppCategory.Health, guessedAppCategory("pedometer.stepcounter.calorieburner.pedometerforwalking", ""))
    }

    @Test
    fun croatianAndOtherNamedApps_arePlaced_byPackageAndByName() {
        // The maintainer, 2026-11-21. Google Play's own categories would put
        // Moj Telekom and mGrađani in Office, and Glovo nowhere.
        for ((packageName, category) in listOf(
            "hr.asseco.android.zaba.new" to AppCategory.Finance,
            "hr.asseco.android.ae.otp" to AppCategory.Finance,
            "hr.erstebank.george" to AppCategory.Finance,
            "com.hrvatskitelekom.m.mojT" to AppCategory.Phone,
            "hr.mgradani" to AppCategory.Government,
            "hr.sedamit.hpmbox" to AppCategory.Post,
            "com.glovo" to AppCategory.Food,
            "com.hzppispromobile" to AppCategory.Transit,
            "de.apptiv.business.android.aldi_uk" to AppCategory.Food,
            "superdrug.com.beautycard" to AppCategory.Shopping,
            "com.thomson.mythomson" to AppCategory.Travel,
            "atws.app" to AppCategory.Finance,
            "com.discord" to AppCategory.Social,
            "de.gmx.mobile.android.mail" to AppCategory.Office,
            "at.post.app" to AppCategory.Post,
            "ch.sbb.mobile.android.b2c" to AppCategory.Transit,
            "com.sl.SLBiljetter" to AppCategory.Transit,
            "uk.gov.govuk" to AppCategory.Government,
            "au.gov.ato.mygovid.droid" to AppCategory.Government,
            "au.gov.nsw.service" to AppCategory.Government,
            "au.gov.vic.ptv" to AppCategory.Transit,
            "uk.gov.metoffice.weather.android" to AppCategory.Weather,
            "au.com.opal.travel" to AppCategory.Transit,
            "com.chase.intl" to AppCategory.Finance,
            "hu.otpbank.mobile" to AppCategory.Finance,
            "com.db.pwcc.dbmobile" to AppCategory.Finance,
            "ro.raiffeisen.smartmobile" to AppCategory.Finance,
            "at.erstebank.george" to AppCategory.Finance,
            "hr.asseco.android.jimba.mUCI.bg" to AppCategory.Finance,
            "bg.postbank.mobile.android.release" to AppCategory.Finance,
            "com.danskebank.mobilebank3.uk" to AppCategory.Finance,
            "pl.bzwbk.bzwbk24" to AppCategory.Finance,
            "rs.erstebank.george" to AppCategory.Finance,
            "rs.Raiffeisen.mobile" to AppCategory.Finance,
            "com.posteitaliane.spim" to AppCategory.Finance,
            "it.pagopa.io.app" to AppCategory.Government,
            "fr.vinted" to AppCategory.Shopping,
            "com.france24.androidapp" to AppCategory.News,
            "com.cbouvat.android.saracroche" to AppCategory.Phone,
            "com.aon.sante" to AppCategory.Health,
            "com.starfinanz.mobile.android.pushtan" to AppCategory.Finance,
            "de.deutschepost.postident" to AppCategory.Finance,
            "de.idealo.android" to AppCategory.Shopping,
            "es.fnmtrcm.ceres.certificadoDigitalFNMT" to AppCategory.Government,
            "pt.continente.LojaContinente" to AppCategory.Food,
            "de.zalando.mobile" to AppCategory.Shopping,
            "com.primark.app.prod" to AppCategory.Shopping,
            "pt.segsocial.mobile.segurancasocial" to AppCategory.Government,
            "com.bankinter.portugal.bmb" to AppCategory.Finance,
            "com.BancoDePortugal.BPmobile" to AppCategory.Finance,
            "com.abanca.bm.pt" to AppCategory.Finance,
            "pt.santander.oneappparticulares" to AppCategory.Finance,
            "ie.centra.loyalty" to AppCategory.Food,
            "com.homestoreandmore.hsmapp" to AppCategory.Shopping,
            "uk.co.next.android" to AppCategory.Shopping,
            "com.aimia.android.musgrave" to AppCategory.Rewards,
            "com.marksandspencer.app" to AppCategory.Shopping,
            "com.lidl.eci.lidlplus" to AppCategory.Food,
            "com.hm.goe" to AppCategory.Shopping,
            "com.boots.flagship.android" to AppCategory.Shopping,
            "com.yopeso.lieferando" to AppCategory.Food,
            "com.deliveroo.orderapp" to AppCategory.Food,
            "com.justeat.app.uk" to AppCategory.Food,
            "com.moh.nusukapp" to AppCategory.Travel,
            "ma.marjanemall.marjanemall" to AppCategory.Shopping,
            "org.hicham.salaat" to AppCategory.Reading,
            "com.pakdata.QuranMajeed" to AppCategory.Reading,
            "com.crunchyroll.crunchyroid" to AppCategory.Media,
            "ma.creditagricole.banke" to AppCategory.Finance,
            "com.saphir.baridbankmobile" to AppCategory.Finance,
            "com.andi.alquran.id" to AppCategory.Reading,
            "com.quran.labs.androidquran" to AppCategory.Reading,
            "com.sirma.mobile.bible.android" to AppCategory.Reading,
            "org.lds.ldssa" to AppCategory.Reading,
            "bhagavan.id" to AppCategory.Reading,
            "org.wakingup.android" to AppCategory.Health,
            "com.calm.android" to AppCategory.Health,
            "com.tencent.ig" to AppCategory.Games,
            "com.orange.mobinilandme" to AppCategory.Phone,
            "com.truecaller" to AppCategory.Phone,
            "com.moe.fahem" to AppCategory.Learning,
            "com.lemon.lvoverseas" to AppCategory.Design,
            "com.newleaf.app.android.victor" to AppCategory.Media,
            "com.etisalat" to AppCategory.Phone,
            "com.egyptianbanks.instapay" to AppCategory.Finance,
            "com.meesho.supply" to AppCategory.Shopping,
            "com.phonepe.app" to AppCategory.Finance,
            "com.flipkart.android" to AppCategory.Shopping,
            "com.rapido.passenger" to AppCategory.Car,
            "com.grofers.customerapp" to AppCategory.Food,
            "net.one97.paytm" to AppCategory.Finance,
            "com.boi.ua.android" to AppCategory.Finance,
            "com.naviapp" to AppCategory.Finance,
            "ae.uaepass.mainapp" to AppCategory.Government,
            "im.thebot.messenger" to AppCategory.Social,
            "com.Etisalat.ETIDA" to AppCategory.Phone,
            "com.fab.personalbanking" to AppCategory.Finance,
            "com.rak" to AppCategory.Finance,
            "com.uaemoi.smartservices" to AppCategory.Government,
            "ae.gov.mol" to AppCategory.Government,
            "com.qmobility.darbx" to AppCategory.Car,
            "com.metaztech.fawoo" to AppCategory.Social,
            "com.etisalat.ewallet" to AppCategory.Finance,
            "com.facebook.stella" to AppCategory.Ai,
            "io.playout" to AppCategory.Rewards,
            "com.dealseekorg.dealseek" to AppCategory.Shopping,
            "com.getyourguide.android" to AppCategory.Travel,
            "com.viator.mobile.android" to AppCategory.Travel,
            "com.soarinfotech.coupert" to AppCategory.Shopping,
            "com.adidas.app" to AppCategory.Shopping,
            "com.fsn.nds" to AppCategory.Shopping,
            "com.zhiliaoapp.musically.go" to AppCategory.Social,
            "com.camerasideas.instashot" to AppCategory.Design,
            "com.instagram.moonshot" to AppCategory.Social,
            "com.instagram.basel" to AppCategory.Design,
            "com.facebook.lite" to AppCategory.Social,
            "com.tideplatform.banking" to AppCategory.Finance,
            "com.xtb.xmobile2" to AppCategory.Finance,
            "mtnft.momo.consumer" to AppCategory.Finance,
            "com.momosa" to AppCategory.Finance,
            "cn.xender" to AppCategory.Tools,
            "ng.mtn.nextgen" to AppCategory.Phone,
            "com.mtngh.mymtn" to AppCategory.Phone,
            "capitec.acuity.mobile.prod" to AppCategory.Finance,
            "za.co.gotyme" to AppCategory.Finance,
            "ph.com.gotyme" to AppCategory.Finance,
            "com.remitly.androidapp" to AppCategory.Finance,
            "com.transferwise.android" to AppCategory.Finance,
            "com.revolut.junior" to AppCategory.Finance,
            "ee.mtakso.client" to AppCategory.Car,
            "com.mtn1app" to AppCategory.Phone,
            "com.globe.gcash.android" to AppCategory.Finance,
            "egov.app" to AppCategory.Government,
            "ph.seabank.seabank" to AppCategory.Finance,
            "com.roblox.client" to AppCategory.Games,
            "com.paymaya" to AppCategory.Finance,
            "com.bpi.ng.app" to AppCategory.Finance,
            "com.shopify.arrive" to AppCategory.Shopping,
            "com.olioex.android" to AppCategory.Food,
            "nl.rijksoverheid.digid.pub" to AppCategory.Government,
            "nl.rtl.videoland.v2" to AppCategory.Media,
            "nl.tpp.mobile.android" to AppCategory.Post,
            "com.mysugr.android.companion" to AppCategory.Health,
            "digifit.android.virtuagym.pro.bravofitcentral" to AppCategory.Health,
            "uk.co.virginactive.android" to AppCategory.Health,
            "com.dexcom.g7" to AppCategory.Health,
            "com.garmin.android.apps.connectmobile" to AppCategory.Health,
            "org.kp.m" to AppCategory.Health,
            "org.sharethemeal.app" to AppCategory.Charity,
            "at.itsv.mobile.meineoegk" to AppCategory.Health,
            "at.itsv.mobile.meinebvaeb" to AppCategory.Health,
            "com.betterhelp" to AppCategory.Health,
            "at.svagw.mobile.svaapp" to AppCategory.Health,
            "com.yazio.android" to AppCategory.Health,
            "com.pg.oralb.oralbapp" to AppCategory.Health,
            "de.komoot.android" to AppCategory.Cycling,
            "com.xiaomi.wearable" to AppCategory.Health,
            "com.nianticlabs.pikmin" to AppCategory.Games,
            "com.nianticlabs.pokemongo" to AppCategory.Games,
            "com.groundspeak.geocaching.intro" to AppCategory.Health,
            "org.iggymedia.periodtracker" to AppCategory.Health,
            "com.clue.android" to AppCategory.Health,
            "com.huckleberry_labs.app" to AppCategory.Kids,
            "com.blinkslabs.blinkist.android" to AppCategory.Reading,
            "com.wsl.noom" to AppCategory.Health,
            "com.elevatelabs.geonosis" to AppCategory.Health,
            "com.lescadeaux.kegel" to AppCategory.Health,
            "com.whatnot_mobile" to AppCategory.Shopping,
            "com.emmaprod" to AppCategory.Finance,
            "com.pktmny.mobile" to AppCategory.Finance,
            "com.sameday.client.mobile" to AppCategory.Post,
            "com.fantastico.fclub" to AppCategory.Shopping,
            "trendyol.com" to AppCategory.Shopping,
            "bg.dskbank.DSKmobile" to AppCategory.Finance,
            "com.evrotrust.app" to AppCategory.Government,
            "com.grppl.android.shell.halifax" to AppCategory.Finance,
            "co.uk.Nationwide.Mobile" to AppCategory.Finance,
            "se.nordea.mobilebank" to AppCategory.Finance,
            "ru.otpbank.mobile" to AppCategory.Finance,
            "lv.rimi.card" to AppCategory.Shopping,
            "com.omnivagroup.app" to AppCategory.Post,
            "ee.ria.eesti.app" to AppCategory.Government,
            "com.smart_id" to AppCategory.Security,
            "com.scotiabank.banking" to AppCategory.Finance,
            "com.vaesttrafik.vaesttrafik" to AppCategory.Transit,
            "nl.ns.android.activity" to AppCategory.Transit,
            "no.posten.sporing.controller" to AppCategory.Post,
            "nz.co.nzpost" to AppCategory.Post,
            "co.uk.postoffice.PostOfficeApp" to AppCategory.Post,
            "com.nth.swisspost" to AppCategory.Post,
            "jp.co.mcdonalds.android" to AppCategory.Food,
            "it.italotreno" to AppCategory.Transit,
            "de.flixbus.app" to AppCategory.Transit,
            "de.hafas.android.db" to AppCategory.Transit,
            "com.mgi.moneygram" to AppCategory.Finance,
            "com.fiverr.fiverr" to AppCategory.Jobs,
            "com.shell.sitibv.retail" to AppCategory.Car,
            "com.bp.mobile.bpme.uk" to AppCategory.Car,
            "com.pnc.ecommerce.mobile" to AppCategory.Finance,
            "com.symantec.lifelock.memberapp" to AppCategory.Security,
            "com.etekcity.vesyncplatform" to AppCategory.Home,
            "com.tplink.iot" to AppCategory.Home,
            "com.tuya.smartlife" to AppCategory.Home,
            "com.ancestry.android.apps.ancestry" to AppCategory.FamilyHistory,
            "com.twentythreeandme.app" to AppCategory.FamilyHistory,
            "air.com.myheritage.mobile" to AppCategory.FamilyHistory,
            "org.familysearch.mobile" to AppCategory.FamilyHistory,
            "com.morganstanley.clientmobile.prod" to AppCategory.Finance,
            "com.swissquote.android" to AppCategory.Finance,
            "com.kraken.invest.app" to AppCategory.Finance,
            "co.mona.android" to AppCategory.Finance,
            "com.tradingview.tradingviewapp" to AppCategory.Finance,
            "com.yahoo.mobile.client.android.finance" to AppCategory.Finance,
            "com.bloomberg.android.plus" to AppCategory.Finance,
            "com.moneyboxapp" to AppCategory.Finance,
            "com.ft.news" to AppCategory.News,
            "ch.nzz.mobile" to AppCategory.News,
            "de.zeit.online" to AppCategory.News,
            "nz.co.radionz.android" to AppCategory.News,
            "uk.co.uktv.dave" to AppCategory.Media,
            "com.google.android.apps.pixel.aurelius" to AppCategory.Notes,
            "com.transsion.notebook" to AppCategory.Notes,
            "com.google.android.keep" to AppCategory.Notes,
            "com.todoist" to AppCategory.Notes,
            "notion.id" to AppCategory.Notes,
            "com.anydo" to AppCategory.Notes,
            "com.ticktick.task" to AppCategory.Notes,
            "com.trello" to AppCategory.Notes,
            "com.callpod.android_apps.keeper" to AppCategory.Security,
            "com.antivirus" to AppCategory.Security,
            "com.trendmicro.fraudbuster" to AppCategory.Security,
            "com.intuit.quickbooks" to AppCategory.Finance,
            "org.me.mobiexpensifyg" to AppCategory.Finance,
            "com.lumoslabs.lumosity" to AppCategory.Learning,
            "com.workday.workdroidapp" to AppCategory.Jobs,
            "com.xero.payday" to AppCategory.Finance,
            "com.google.android.apps.dynamite" to AppCategory.Social,
            "com.monday.monday" to AppCategory.Notes,
            "com.basecamp.bc3" to AppCategory.Notes,
            "io.unorderly.structured" to AppCategory.Notes,
            "com.habitrpg.android.habitica" to AppCategory.Notes,
            "com.daikineurope.online.controller" to AppCategory.Home,
            "mitsubishi.wifi.android.mitsubishiwifiapp" to AppCategory.Home,
            "com.midea.aircondition.obm" to AppCategory.Home,
            "com.lemonde.androidapp" to AppCategory.News,
            "com.economist.lamarr" to AppCategory.News,
            "com.substack.app" to AppCategory.Reading,
            "com.fullsix.android.labanquepostale.accountaccess" to AppCategory.Finance,
            "com.bochk.app.aos" to AppCategory.Finance,
            "nz.co.bnz.droidbanking" to AppCategory.Finance,
            "com.creditkarma.mobile.international" to AppCategory.Finance,
            "com.citi.mobile.ccc" to AppCategory.Finance,
            "sg.com.hsbc.hsbcsingapore" to AppCategory.Finance,
            "hk.com.hsbc.hsbchkmobilebanking" to AppCategory.Finance,
            "com.microsoft.amp.apps.bingfinance" to AppCategory.Finance,
            "com.roborock.smart" to AppCategory.Home,
            "com.tuya.smart" to AppCategory.Home,
            "com.mcu.reolink" to AppCategory.Home,
            "com.irobot.home.prime" to AppCategory.Home,
            "com.xiaomi.smarthome" to AppCategory.Home,
            "com.lgeha.nuts" to AppCategory.Home,
            "org.dayup.stocks" to AppCategory.Finance,
            "ai.x.grok.bot" to AppCategory.Ai,
            "com.usebounce" to AppCategory.Travel,
            "se.memfrag.app" to AppCategory.Travel,
            "com.tribo.bubbleapp" to AppCategory.Kids,
            "io.elevenlabs.readerapp" to AppCategory.Reading,
            "in.evolve.android" to AppCategory.Health,
            "com.technogym.tgapp" to AppCategory.Health,
            "de.aoksystems.amg" to AppCategory.Health,
            "de.barmergek.serviceapp" to AppCategory.Health,
            "de.aok.digen" to AppCategory.Health,
            "de.tk.apps.android.tkident" to AppCategory.Health,
            "de.tk.tkcoach" to AppCategory.Health,
            "fr.doctolib.www" to AppCategory.Health,
            "net.iplato.mygp" to AppCategory.Health,
            "au.com.hotdoc.android.hotdoc" to AppCategory.Health,
            "com.doctorcareanywhere" to AppCategory.Health,
            "com.practo.fabric" to AppCategory.Health,
            "com.NetmedsMarketplace.Netmeds" to AppCategory.Health,
            "com.naturitas.android" to AppCategory.Shopping,
            "com.nbty.hollandbarret" to AppCategory.Shopping,
            "com.conceptivapps.blossom" to AppCategory.Learning,
            "com.bupa.bupatouch" to AppCategory.Health,
            "com.wam.android" to AppCategory.Health,
            "com.dailyyoga.inc" to AppCategory.Health,
            "com.lasoo.android.target" to AppCategory.Shopping,
            "com.isoton.lebara" to AppCategory.Phone,
            "com.weightwatchers.mobile" to AppCategory.Health,
            "com.slimmingworld.uk" to AppCategory.Health,
            "com.fatsecret.android" to AppCategory.Health,
            "au.co.myvirginactive" to AppCategory.Health,
            "com.star.customer_app" to AppCategory.Health,
            "com.aetna.aetnahealth" to AppCategory.Health,
            "org.stanfordhealthcare.myhealth" to AppCategory.Health,
            "bot.touchkin" to AppCategory.Health,
            "com.talkspace.talkspaceapp" to AppCategory.Health,
            "us.regain" to AppCategory.Health,
            "com.bstro.MindShift" to AppCategory.Health,
            "com.myfitnesspal.android" to AppCategory.Health,
            "com.instagram.android" to AppCategory.Social,
            "com.canva.editor" to AppCategory.Design,
            "com.amazon.kindle" to AppCategory.Reading,
            "com.swisscom.myswisscom" to AppCategory.Phone,
            "ch.salt.my" to AppCategory.Phone,
            "com.orange.orangeetmoi" to AppCategory.Phone,
            "au.net.abc.iview" to AppCategory.Media,
            "bbc.iplayer.android" to AppCategory.Media,
        )) {
            assertEquals(packageName, category, knownAppCategory(packageName, "Acme"))
        }
        for ((label, category) in listOf(
            "m-zaba" to AppCategory.Finance,
            "OTPgo" to AppCategory.Finance,
            "George Croatia" to AppCategory.Finance,
            "Moj Telekom HR" to AppCategory.Phone,
            "mGrađani" to AppCategory.Government,
            "ePošta - Hrvatska pošta" to AppCategory.Post,
            "Glovo: Food & Grocery Delivery" to AppCategory.Food,
            "Njuškalo" to AppCategory.Shopping,
            "ALDI UK" to AppCategory.Food,
            "ALDI Mobile" to AppCategory.Phone,
            "Superdrug - Beauty and Health" to AppCategory.Shopping,
            "TUI: Holidays, Travel, Flights" to AppCategory.Travel,
            "IBKR Mobile" to AppCategory.Finance,
            "DEGIRO" to AppCategory.Finance,
            "GMX Mail" to AppCategory.Office,
            "SBB Mobile" to AppCategory.Transit,
            "Österreichische Post" to AppCategory.Post,
            "My Swisscom" to AppCategory.Phone,
            "MySalt." to AppCategory.Phone,
            "Orange et moi" to AppCategory.Phone,
            "ABC iview" to AppCategory.Media,
            "My Centra" to AppCategory.Food,
            "Real Rewards" to AppCategory.Rewards,
            "Home Store + More" to AppCategory.Shopping,
            "M&S - Fashion, Food & Homeware" to AppCategory.Shopping,
            "Lidl Plus" to AppCategory.Food,
            "Al Quran Indonesia" to AppCategory.Reading,
            "Alquran" to AppCategory.Reading,
            "The Holy Bible KJV" to AppCategory.Reading,
            "Bíblia Sagrada Offline" to AppCategory.Reading,
            "Le Coran" to AppCategory.Reading,
            "My etisalat" to AppCategory.Phone,
            "ReelShort - Stream Drama & TV" to AppCategory.Media,
            "InstaPay Egypt" to AppCategory.Finance,
            "ShareTheMeal: Charity Donate" to AppCategory.Charity,
            "GoFundMe" to AppCategory.Charity,
            "British Red Cross" to AppCategory.Charity,
            "Donate to Charity" to AppCategory.Charity,
            "Pedometer - Step Counter" to AppCategory.Health,
            "Step Tracker & Walking" to AppCategory.Health,
            "Period Tracker & Cycle" to AppCategory.Health,
            "Kegel Trainer" to AppCategory.Health,
            "Lebara Australia" to AppCategory.Phone,
            "VeSync" to AppCategory.Home,
            "MyHeritage" to AppCategory.FamilyHistory,
            "Family Tree Maker" to AppCategory.FamilyHistory,
            "Genealogy Notes" to AppCategory.FamilyHistory,
            "LG ThinQ" to AppCategory.Home,
            "Xiaomi Home" to AppCategory.Home,
            "Grok" to AppCategory.Ai,
            "Webull" to AppCategory.Finance,
            "Online Therapy & Counseling" to AppCategory.Health,
            "Mental Health Support" to AppCategory.Health,
            "Pelvic Floor Exercises" to AppCategory.Health,
            "Home Workout - No Equipment" to AppCategory.Health,
            "Blinkit: Grocery in minutes" to AppCategory.Food,
            "Rapido: Bike-Taxi, Auto & Cabs" to AppCategory.Car,
            "Meesho: Online Shopping App" to AppCategory.Shopping,
            "etisalat money UAE" to AppCategory.Finance,
            "etisalat UAE" to AppCategory.Phone,
            "UAE PASS" to AppCategory.Government,
            "MTN MoMo SA" to AppCategory.Finance,
            "myMTN Ghana" to AppCategory.Phone,
            "Xender - Quick Share, Transfer" to AppCategory.Tools,
        )) {
            assertEquals(label, category, knownAppCategory("com.example.hr", label))
        }
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

    @Test
    fun appsThatCouldGoEitherWay_areFiledInTheirFirstFolder_untilTheUserDecides() {
        for ((packageName, folders) in EITHER_WAY_PACKAGES) {
            assertEquals(packageName, 2, folders.distinct().size)
            assertEquals(packageName, folders.first(), knownAppCategory(packageName))
        }
    }
}
