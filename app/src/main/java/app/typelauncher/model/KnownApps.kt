package app.typelauncher

/**
 * Folders for apps we can place without asking anyone: well-known apps by
 * package, apps whose name says what they are ("Samsung Health", "Gallery"),
 * and, as a last resort, words in the package name. All of it works on every
 * phone, offline, so lower-end phones without on-device AI still get most
 * apps sorted (maintainer, 2026-11-19), and phones with it get the common
 * apps right whatever the model says.
 */

/**
 * The folder an app always goes in, ahead of the AI: a well-known app by its
 * package (Netflix in Media, Waze in Transport), one from a games publisher
 * by its package prefix (Netflix's games, `com.netflix.NGP.*`, in Games), or
 * one whose [label] has a word that says what it is ("Samsung Internet",
 * "Messages"). Null when none of these applies.
 */
internal fun knownAppCategory(packageName: String, label: String = ""): AppCategory? =
    KnownApps.byPackage[packageName]
        ?: KNOWN_PACKAGE_PREFIXES.firstOrNull { (prefix, _) -> packageName.startsWith(prefix) }?.second
        ?: labelCategory(label)

/**
 * [knownAppCategory] for [this] app. A pinned website carries the package of
 * the browser that pinned it, which says nothing about the site, so only its
 * name counts.
 */
internal fun InstalledApp.knownCategory(): AppCategory? =
    knownAppCategory(if (isShortcut) "" else packageName, name)

/** [guessedAppCategory] for [this] app; a pinned website by its name alone. */
internal fun InstalledApp.guessedCategory(): AppCategory? =
    guessedAppCategory(if (isShortcut) "" else packageName, name)

/**
 * A guess from words in the app's package name and label ("com.example.mybank"
 * is Finance), for an app nothing else placed: only after the user, the rules
 * above, the AI and the app's own manifest have had their say.
 */
internal fun guessedAppCategory(packageName: String, label: String): AppCategory? {
    val words = (packageName.split(PACKAGE_WORD_BREAK) + label.split(LABEL_WORD_BREAK))
        .map { it.lowercase() }
        .filter { it.length >= 3 }
    for ((category, stems) in GUESS_RULES) {
        if (words.any { word -> stems.any { stem -> stemMatches(word, stem) } }) {
            return category
        }
    }
    return null
}

// A stem of five letters or more may sit anywhere in a word ("mobilebanking");
// one of four must start it ("games"), since "eats" sits inside "beats"; a
// shorter one must be the whole word ("gov", not "govee").
private fun stemMatches(word: String, stem: String): Boolean = when {
    stem in WORD_START_ONLY -> word.startsWith(stem)
    stem.length >= 5 -> stem in word
    stem.length == 4 -> word.startsWith(stem)
    else -> word == stem
}

// A word in the label that says plainly what the app is. One of five letters
// or more may sit anywhere in a word ("MyFitnessPal"); a shorter one must be
// a whole word or end one ("PureGym", "SkyNews"), so "Newsom" files nothing.
private fun labelCategory(label: String): AppCategory? {
    if (label.isBlank()) return null
    val words = label.lowercase().split(LABEL_WORD_BREAK)
    // Brands first, as whole words or runs of them: "Fly Delta", "My Bupa",
    // "Priority Pass", and "Royal Mail" before "mail" makes it email.
    val padded = words.filter { it.isNotEmpty() }.joinToString(" ", prefix = " ", postfix = " ")
    // The phone app itself, whatever package it comes in.
    if (padded == " phone " || padded == " dialer ") return AppCategory.Phone
    LABEL_BRANDS.firstOrNull { (brand, _) -> " $brand " in padded }?.let { return it.second }
    return LABEL_WORDS.firstOrNull { (keyword, _) ->
        words.any { word ->
            when {
                keyword in WHOLE_WORD_ONLY -> word == keyword
                keyword in WORD_START_ONLY -> word.startsWith(keyword)
                keyword.length >= 5 -> keyword in word
                else -> word.endsWith(keyword)
            }
        }
    }?.second
}

// Brands whose app names say nothing else about them (maintainer,
// 2026-11-19): matched as whole words, so "unifi" isn't "Unified Remote".
private val LABEL_BRANDS: List<Pair<String, AppCategory>> = listOf(
    "delta", "ryanair", "jetblue", "jetstar", "easyjet", "qantas", "emirates", "lufthansa", "klm",
    "southwest", "aeromexico", "aircanada", "westjet", "vueling", "wizzair", "norse",
    "wanderlog", "priority pass", "tripit",
).map { it to AppCategory.Travel } + listOf(
    "bupa" to AppCategory.Health,
    "hca" to AppCategory.Health,
    "nhs" to AppCategory.Health,
    "unifi" to AppCategory.Internet,
    "saily" to AppCategory.Phone,
    "esim" to AppCategory.Phone,
    "suzerain" to AppCategory.Games,
    // A newspaper, before "courier" makes it Post.
    "courier mail" to AppCategory.News,
    "security camera" to AppCategory.Home,
    "home security" to AppCategory.Home,
    "tfl" to AppCategory.Transport,
    "transperth" to AppCategory.Transport,
    // AI assistants by name, now that "AI" alone doesn't file an app.
    "claude" to AppCategory.Ai,
    "perplexity" to AppCategory.Ai,
    "grok" to AppCategory.Ai,
    "deepseek" to AppCategory.Ai,
    "meta ai" to AppCategory.Ai,
    "le chat" to AppCategory.Ai,
) + listOf(
    // Mobile carriers, by brand (maintainer, 2026-11-19).
    "ee", "vodafone", "telstra", "optus", "t mobile", "verizon", "at t", "o2", "giffgaff", "lebara",
    "lycamobile", "2degrees", "spark nz", "one nz", "skinny mobile", "rogers", "telus", "koodo",
    "freedom mobile", "boost mobile", "cricket wireless", "mint mobile", "tesco mobile", "sky mobile",
    "voxi", "smarty mobile", "amaysim", "aldi mobile", "id mobile", "airalo", "truecaller",
).map { it to AppCategory.Phone } + listOf(
    "thefork" to AppCategory.Food,
    "the fork" to AppCategory.Food,
    "opentable" to AppCategory.Food,
    "pret" to AppCategory.Food,
    "fire tv" to AppCategory.Media,
    "antigravity" to AppCategory.Code,
    // Parking; ahead of "phone", which would make it Phone.
    "paybyphone" to AppCategory.Transport,
) + listOf(
    // Password managers, authenticators and privacy monitoring (maintainer,
    // 2026-11-19). Not "keeper" (goalkeeper games) or "aura" (Aura Frames).
    "1password", "bitwarden", "lastpass", "dashlane", "keepass", "keepassdx", "keepass2android",
    "nordpass", "proton pass", "enpass", "roboform", "authy", "aegis", "2fas", "yubico",
    "norton", "avast", "avg", "malwarebytes", "mcafee", "kaspersky", "bitdefender", "incogni",
    "deleteme", "lifelock", "okta verify",
).map { it to AppCategory.Security } + listOf(
    // Games whose names sound like something else (maintainer, 2026-11-19).
    "mini metro" to AppCategory.Games,
    "mini motorways" to AppCategory.Games,
    "darkside detective" to AppCategory.Games,
) + listOf(
    // Post and couriers (maintainer, 2026-11-19).
    "australia post", "auspost", "royal mail", "usps", "fedex", "ups", "dhl", "dpd", "evri", "hermes",
    "parcelforce", "canada post", "nz post", "an post", "post office", "aramex", "sendle", "startrack",
    "couriers please", "yodel", "gls", "deutsche post", "la poste", "postnl", "postnord", "correos",
    "posti", "purolator", "packlink",
).map { it to AppCategory.Post }

// Keywords that must start a word: "ebook" sits inside "Facebook" and
// "Notebook" (Codex on PR #866), "sport" inside "Transport" and "Passport"
// (Codex on PR #875), "parent" inside "Transparent".
private val WORD_START_ONLY = setOf("ebook", "parent", "sport")

// Keywords that must be a whole word: "git" ends "legit", and "code" a
// "barcode" scanner.
private val WHOLE_WORD_ONLY = setOf("code", "git", "ssh", "qr", "ai")

private val LABEL_WORD_BREAK = Regex("""[^\p{L}\p{N}]+""")
private val PACKAGE_WORD_BREAK = Regex("""[._\-]+""")

// In order: the first word found decides ("Health News" is Health).
private val LABEL_WORDS = listOf(
    "health" to AppCategory.Health,
    "fitness" to AppCategory.Health,
    "gym" to AppCategory.Health,
    "workout" to AppCategory.Health,
    "authenticator" to AppCategory.Security,
    "password" to AppCategory.Security,
    "antivirus" to AppCategory.Security,
    "parcel" to AppCategory.Post,
    "courier" to AppCategory.Post,
    "patient" to AppCategory.Health,
    "peloton" to AppCategory.Health,
    "browser" to AppCategory.Internet,
    "internet" to AppCategory.Internet,
    "vpn" to AppCategory.Internet,
    "gallery" to AppCategory.Photos,
    "photos" to AppCategory.Photos,
    // Home security cameras before "camera".
    "surveillance" to AppCategory.Home,
    "cctv" to AppCategory.Home,
    "camera" to AppCategory.Photos,
    // Sports and betting before "news" and "casino": "BBC Sport - News &
    // Live Scores", "bet365 - Sportsbook & Casino".
    "sport" to AppCategory.Sports,
    "scores" to AppCategory.Sports,
    "betting" to AppCategory.Sports,
    "news" to AppCategory.News,
    "messages" to AppCategory.Social,
    "messenger" to AppCategory.Social,
    "messaging" to AppCategory.Social,
    "weather" to AppCategory.Weather,
    "maps" to AppCategory.Transport,
    "parking" to AppCategory.Transport,
    "transit" to AppCategory.Transport,
    "transport" to AppCategory.Transport,
    "airways" to AppCategory.Travel,
    "airlines" to AppCategory.Travel,
    "airline" to AppCategory.Travel,
    // "Flights", "Flightradar24".
    "flight" to AppCategory.Travel,
    "music" to AppCategory.Music,
    "audiobook" to AppCategory.Reading,
    "kindle" to AppCategory.Reading,
    "ebook" to AppCategory.Reading,
    "comics" to AppCategory.Reading,
    "manga" to AppCategory.Reading,
    // "Library", "Libraries".
    "librar" to AppCategory.Reading,
    "libby" to AppCategory.Reading,
    "podcasts" to AppCategory.Music,
    "calculator" to AppCategory.Tools,
    "clock" to AppCategory.Tools,
    "calendar" to AppCategory.Productivity,
    "email" to AppCategory.Productivity,
    "mail" to AppCategory.Productivity,
    "wallet" to AppCategory.Finance,
    "bank" to AppCategory.Finance,
    "banking" to AppCategory.Finance,
    // A QR code app is a tool, not Code.
    "qr" to AppCategory.Tools,
    "coding" to AppCategory.Code,
    "programming" to AppCategory.Code,
    "developer" to AppCategory.Code,
    "terminal" to AppCategory.Code,
    "code" to AppCategory.Code,
    "git" to AppCategory.Code,
    "ssh" to AppCategory.Code,
    // Not the word "AI": it names a feature far more often than an
    // assistant ("AI Photo Editor", "AI Tutor"), so AI apps go by name and
    // package instead. Not "Gemini", which is also a crypto exchange;
    // Google's is in the table.
    "chatgpt" to AppCategory.Ai,
    "copilot" to AppCategory.Ai,
    // Not "phone" or "mobile": far more banks, games and utilities say so
    // ("RBC Mobile", "Find My Phone") than carriers, which go by brand.
    "telecom" to AppCategory.Phone,
)

// Publishers whose apps are games, by package prefix.
private val KNOWN_PACKAGE_PREFIXES = listOf(
    "com.netflix.NGP." to AppCategory.Games,
    "com.supercell." to AppCategory.Games,
    "com.king." to AppCategory.Games,
    "com.playrix." to AppCategory.Games,
    "com.zynga." to AppCategory.Games,
    "com.rovio." to AppCategory.Games,
    "com.gameloft." to AppCategory.Games,
    "com.ea.game." to AppCategory.Games,
    "com.ea.gp." to AppCategory.Games,
    "com.epicgames." to AppCategory.Games,
    "com.ubisoft." to AppCategory.Games,
    "com.outfit7." to AppCategory.Games,
    "com.halfbrick." to AppCategory.Games,
    "com.miniclip." to AppCategory.Games,
    "com.sega." to AppCategory.Games,
    "com.bandainamcoent." to AppCategory.Games,
    "com.square_enix." to AppCategory.Games,
    "com.scopely." to AppCategory.Games,
    "com.playtika." to AppCategory.Games,
    "com.igg." to AppCategory.Games,
    "com.lilithgame." to AppCategory.Games,
    "com.HoYoverse." to AppCategory.Games,
    "com.miHoYo." to AppCategory.Games,
    "com.tocaboca." to AppCategory.Kids,
    // Dinosaur Polo Club: Mini Metro, Mini Motorways.
    "nz.co.codepoint." to AppCategory.Games,
)

// Fallback stems, in order: the first that a package or label word matches
// (see stemMatches) files the app.
private val GUESS_RULES = listOf(
    AppCategory.Kids to listOf("kids", "toddler", "children", "baby", "pregnancy", "parent", "parenting"),
    AppCategory.Games to listOf("game", "games", "solitaire", "sudoku", "mahjong", "chess", "puzzle", "casino", "slots", "poker", "crossword"),
    AppCategory.Weather to listOf("weather", "forecast", "radar"),
    AppCategory.Government to listOf("gov", "government", "council"),
    AppCategory.Finance to listOf("bank", "banking", "finance", "invest", "trading", "crypto", "wallet", "insurance", "mortgage", "budget"),
    AppCategory.Food to listOf("food", "recipe", "pizza", "eats", "takeaway", "grocery", "groceries", "restaurant", "burger", "coffee"),
    AppCategory.Music to listOf("music", "radio", "podcast", "karaoke"),
    AppCategory.Media to listOf("movie", "movies", "stream", "drama", "dramas", "series"),
    AppCategory.Photos to listOf("photo", "camera", "gallery", "selfie"),
    AppCategory.Sports to listOf("sport", "football", "soccer", "basketball", "cricket", "rugby", "nfl", "nba", "espn"),
    AppCategory.Health to listOf("health", "fitness", "gym", "workout", "yoga", "meditation", "sleep", "pharmacy", "medical", "doctor", "patient"),
    AppCategory.Learning to listOf("learn", "school", "education", "edu", "dictionary", "language", "course", "study", "flashcards"),
    AppCategory.Pets to listOf("pet", "pets", "dog", "dogs", "vet", "petcare"),
    AppCategory.Transport to listOf("transit", "metro", "rail", "railway", "bus", "taxi", "parking", "maps", "navigation", "scooter", "car", "cars", "vehicle", "charging", "fuel", "petrol"),
    AppCategory.Travel to listOf("airline", "airways", "flight", "hotel", "travel", "booking"),
    AppCategory.Reading to listOf("ebook", "audiobook", "kindle", "comic", "manga", "novel", "librar"),
    AppCategory.Shopping to listOf("shop", "deals", "coupon", "marketplace"),
    AppCategory.Code to listOf("coding", "programming", "developer", "terminal", "ssh", "git", "python", "javascript"),
    AppCategory.Internet to listOf("browser", "internet", "vpn", "wifi", "speedtest"),
    AppCategory.Phone to listOf("telecom", "dialer", "esim"),
    AppCategory.Post to listOf("parcel", "courier", "postal", "freight"),
    AppCategory.News to listOf("news", "newspaper", "headlines"),
    AppCategory.Social to listOf("chat", "messenger", "messaging", "messages", "social", "dating"),
    AppCategory.Productivity to listOf("mail", "email", "calendar", "notes", "docs", "todo", "tasks", "office", "pdf", "meeting"),
    AppCategory.Home to listOf("smarthome", "thermostat", "doorbell", "realestate", "property", "energy"),
    AppCategory.Tools to listOf("calculator", "clock", "alarm", "flashlight", "torch", "files", "filemanager", "keyboard", "cleaner", "battery", "backup"),
    AppCategory.Security to listOf("authenticator", "password", "passwords", "antivirus", "malware"),
)

/** Well-known apps by package. A wrong or retired package just never matches. */
internal object KnownApps {
    val byPackage: Map<String, AppCategory> by lazy {
        HashMap<String, AppCategory>().apply {
            for ((category, packages) in KNOWN_PACKAGES) {
                for (packageName in packages.split(WHITESPACE)) {
                    if (packageName.isNotEmpty()) putIfAbsent(packageName, category)
                }
            }
        }
    }

    private val WHITESPACE = Regex("""\s+""")
}

private val KNOWN_PACKAGES = listOf(
    AppCategory.Media to """
        com.netflix.mediaclient com.google.android.youtube com.amazon.avod.thirdpartyclient
        com.disney.disneyplus com.wbd.stream com.hbo.hbonow com.hulu.plus com.cbs.app
        com.peacocktv.peacockandroid com.crunchyroll.crunchyroid com.tubitv tv.pluto.android
        com.plexapp.android tv.twitch.android.app org.videolan.vlc com.mxtech.videoplayer.ad
        com.google.android.videos com.vimeo.android.videoapp com.dailymotion.dailymotion bbc.iplayer.android
        com.channel4.ondemand air.ITVMobilePlayer com.channel5.my5 com.apple.atve.androidtv.appletv
    """,
    AppCategory.Music to """
        com.spotify.music com.google.android.apps.youtube.music com.amazon.mp3
        com.apple.android.music deezer.android.app com.aspiro.tidal com.soundcloud.android
        com.shazam.android com.pandora.android com.bandcamp.android tunein.player
        com.clearchannel.iheartradio.controller com.bbc.sounds au.com.shiftyjelly.pocketcasts
        com.musixmatch.android.lyrify com.sonos.acr2
    """,
    AppCategory.Photos to """
        com.google.android.apps.photos com.google.android.GoogleCamera com.sec.android.app.camera
        com.sec.android.gallery3d com.adobe.lrmobile com.niksoftware.snapseed com.vsco.cam
        com.picsart.studio com.amazon.clouddrive.photos com.google.android.apps.photos.scanner
    """,
    AppCategory.Social to """
        com.whatsapp com.whatsapp.w4b com.facebook.katana com.facebook.orca com.instagram.android
        com.instagram.barcelona com.twitter.android com.snapchat.android com.zhiliaoapp.musically
        com.ss.android.ugc.trill org.telegram.messenger org.thoughtcrime.securesms com.discord
        com.reddit.frontpage com.linkedin.android com.pinterest com.tumblr com.bereal.ft
        com.google.android.apps.messaging com.samsung.android.messaging com.viber.voip
        jp.naver.line.android com.tencent.mm com.kakao.talk com.skype.raider com.tinder
        com.bumble.app co.hinge.app xyz.blueskyweb.app org.joinmastodon.android com.nextdoor
        com.google.android.contacts
    """,
    AppCategory.Productivity to """
        com.google.android.gm com.microsoft.office.outlook com.google.android.calendar
        com.google.android.keep com.google.android.apps.docs com.google.android.apps.docs.editors.docs
        com.google.android.apps.docs.editors.sheets com.google.android.apps.docs.editors.slides
        com.microsoft.office.word com.microsoft.office.excel com.microsoft.office.powerpoint
        com.microsoft.office.officehubrow com.microsoft.teams com.Slack us.zoom.videomeetings
        com.google.android.apps.tachyon notion.id com.todoist com.microsoft.todos com.evernote
        com.dropbox.android com.microsoft.skydrive com.adobe.reader com.trello com.asana.app
        com.google.android.apps.tasks com.samsung.android.app.notes md.obsidian
        com.yahoo.mobile.client.android.mail ch.protonmail.android com.google.android.apps.dynamite
        com.microsoft.office.onenote com.atlassian.android.jira.core
    """,
    AppCategory.Finance to """
        com.paypal.android.p2pmobile com.venmo com.squareup.cash com.robinhood.android
        com.coinbase.android com.binance.dev co.uk.getmondo com.revolut.revolut
        com.transferwise.android com.starlingbank.android com.google.android.apps.walletnfcrel
        com.google.android.apps.nbu.paisa.user com.chase.sig.android com.wf.wellsfargomobile
        com.infonow.bofa com.konylabs.capitalone com.americanexpress.android.acctsvcs.us
        com.citi.citimobile com.usaa.mobile.android.usaa com.discoverfinancial.mobile
        com.sofi.mobile com.creditkarma.mobile com.youneedabudget.evergreen.app
        com.fidelity.android com.etrade.mobilepro.activity com.schwab.mobile com.klarna.android
        com.affirm.central uk.co.hsbc.hsbcukmobilebanking com.barclays.android.barclaysmobilebanking
        com.grppl.android.shell.CMBlloydsTSB73 uk.co.santander.santanderUK
        com.rbs.mobile.android.natwest com.nationwide.mobile.android.nationwide
        de.number26.android com.zellepay.zelle com.samsung.android.spay com.onedebit.chime
        com.etoro.openbook com.avuscapital.trading212
    """,
    AppCategory.Shopping to """
        com.amazon.mShop.android.shopping com.ebay.mobile com.etsy.android
        com.alibaba.aliexpresshd com.einnovation.temu com.zzkko com.walmart.android
        com.target.ui com.bestbuy.android com.contextlogic.wish com.vinted com.depop
        com.poshmark.app com.shopify.arrive com.homeretailgroup.argos.android com.asos.app
        com.inditex.zara com.hm.goe com.nike.omega com.costco.app.android com.homedepot
        com.lowes.android com.groupon com.offerup com.mercariapp.mercari com.wayfair.wayfair
    """,
    AppCategory.Food to """
        com.ubercab.eats com.dd.doordash com.grubhub.android com.deliveroo.orderapp
        com.justeat.app.uk com.instacart.client com.starbucks.mobilecard com.mcdonalds.app
        uk.co.dominos.android com.dominospizza com.yelp.android com.opentable
        com.tesco.grocery.view com.app.tgtg com.chickfila.cfaflagship com.chipotle.ordering
    """,
    AppCategory.Travel to """
        com.booking com.airbnb.android com.expedia.bookings com.tripadvisor.tripadvisor
        com.kayak.android com.hopper.mountainview.play net.skyscanner.android.main
        com.ryanair.cheapflights com.ba.mobile com.delta.mobile.android com.aa.android
        com.united.mobile.android com.southwestairlines.mobile com.hcom.android
        com.marriott.mrt com.hilton.android.hhonors com.ihg.apps.android com.tripit
        com.vacationrentals.homeaway com.ehi.enterprise.android com.jetblue.JetBlueAndroid
        com.jetstar.mobile com.emirates.ek.android com.qantas.qantasapp com.lufthansa.android.lufthansa
        com.klm.mobile.iflyapp com.easyjet.android com.alaskaairlines.android
    """,
    AppCategory.Transport to """
        com.google.android.apps.maps com.waze com.ubercab me.lyft.android ee.mtakso.client
        com.citymapper.app.release com.tranzmate com.thetrainline net.sharewire.parkmobilev2
        com.here.app.maps com.sygic.aura com.limebike com.bird com.teslamotors.tesla
        com.coulombtech com.thetransitapp.droid com.amtrak.rider
        com.google.android.projection.gearhead taxi.android.client com.grabtaxi.passenger
        com.olacabs.customer
    """,
    AppCategory.Weather to """
        com.accuweather.android com.weather.Weather com.handmark.expressweather
        com.windyty.android com.wunderground.android.weather com.yahoo.mobile.client.android.weather
        uk.gov.metoffice.weather.android com.acmeaom.android.myradar
    """,
    AppCategory.News to """
        com.google.android.apps.magazines bbc.mobile.news.uk bbc.mobile.news.ww
        com.cnn.mobile.android.phone com.nytimes.android com.washingtonpost.android com.guardian
        com.thomsonreuters.reuters com.foxnews.android mnn.Android flipboard.app
        com.microsoft.amp.apps.bingnews com.particlenews.newsbreak com.bskyb.skynews.android
    """,
    AppCategory.Health to """
        com.google.android.apps.fitness com.fitbit.FitbitMobile com.strava com.myfitnesspal.android
        com.calm.android com.getsomeheadspace.android com.sec.android.app.shealth com.nike.plusgps
        com.nike.ntc com.garmin.android.apps.connectmobile org.iggymedia.periodtracker
        com.clue.android com.northcube.sleepcycle com.ouraring.oura com.nhs.online.nhsonline
        com.zocdoc.android com.medisafe.android.client com.cvs.launchers.cvs com.wsl.noom
        com.onepeloton.callisto
    """,
    AppCategory.Games to """
        com.mojang.minecraftpe com.roblox.client com.innersloth.spacemafia com.nianticlabs.pokemongo
        com.dts.freefireth com.tencent.ig com.activision.callofduty.shooter com.chess
        org.lichess.mobileapp com.nytimes.crossword com.kiloo.subwaysurf com.imangi.templerun2
        com.moonactive.coinmaster com.valvesoftware.android.steam.community com.scee.psxandroid
        com.microsoft.xboxone.smartglass com.mobile.legends
    """,
    AppCategory.Learning to """
        com.duolingo com.babbel.mobile.android.en org.coursera.android org.khanacademy.android
        com.quizlet.quizletandroid com.google.android.apps.classroom com.udemy.android
        com.languagedrops.drops.international com.busuu.android.enc com.microblink.photomath
        com.brainly org.wikipedia com.google.android.apps.translate com.merriamwebster
        com.ted.android org.edx.mobile com.linkedin.android.learning com.instructure.candroid
        com.ichi2.anki
    """,
    AppCategory.Reading to """
        com.amazon.kindle com.google.android.apps.books com.audible.application
        com.overdrive.mobile.android.libby com.naver.linewebtoon com.kobobooks.android
        com.scribd.app.reader0 com.goodreads com.storytel com.wattpad
        com.blinkslabs.blinkist.android com.ideashower.readitlater.pro org.readera
        com.marvel.comics com.comixology.comics com.hoopladigital.android
    """,
    AppCategory.Ai to """
        com.openai.chatgpt com.google.android.apps.bard com.anthropic.claude
        com.microsoft.copilot ai.perplexity.app.android
    """,
    AppCategory.Code to """
        com.github.android com.termux com.replit.app com.foxdebug.acode ru.iiec.pydroid3
        com.sonelli.juicessh com.server.auditor.ssh.client com.stackexchange.marvin
        com.gitlab.mobile org.connectbot com.aide.ui
    """,
    AppCategory.Phone to """
        com.google.android.dialer com.samsung.android.dialer com.tmobile.pr.mytmobile
        com.vzw.hss.myverizon com.att.myWireless com.google.android.apps.tycho
        com.mint.mobile com.visible.android uk.co.ee.myee com.vodafone.myvodafone uk.co.o2.android.myo2
        com.three.myaccount com.giffgaff.mobile
    """,
    AppCategory.Kids to """
        com.google.android.apps.youtube.kids com.google.android.apps.kids.familylink
        org.pbskids.video org.khankids.android com.microsoft.familysafety
    """,
    AppCategory.Internet to """
        com.android.chrome org.mozilla.firefox com.microsoft.emmx com.brave.browser
        com.opera.browser com.sec.android.app.sbrowser com.duckduckgo.mobile.android
        com.vivaldi.browser org.torproject.torbrowser com.nordvpn.android com.expressvpn.vpn
        ch.protonvpn.android com.cloudflare.onedotonedotonedotone org.zwanoo.android.speedtest
        com.google.android.googlequicksearchbox com.microsoft.bing
    """,
    AppCategory.Home to """
        com.google.android.apps.chromecast.app com.nest.android com.amazon.dee.app com.ringapp
        com.philips.lighting.hue2 com.samsung.android.oneconnect com.ecobee.athenamobile
        com.arlo.app com.hualai com.tplink.kasa_android com.rightmove.android
        com.zillow.android.zillowmap com.trulia.android com.redfin.android
        io.homeassistant.companion.android com.irobot.home com.tado
    """,
    AppCategory.Government to """
        gov.irs
    """,
    AppCategory.Sports to """
        com.espn.score_center com.bleacherreport.android.teamstream
        com.yahoo.mobile.client.android.sportacular com.theathletic com.nbaimd.gametime.nba2011
        com.bamnetworks.mobile.android.gameday.atbat com.mobilefootie.wc2010 de.motain.iliga
        com.sofascore.results com.livescore com.dazn
    """,
    AppCategory.Pets to """
        com.rover.android com.chewy.android org.inaturalist.android
    """,
    AppCategory.Security to """
        com.google.android.apps.authenticator2 com.x8bit.bitwarden com.agilebits.onepassword
        com.azure.authenticator com.authy.authy com.lastpass.lpandroid com.dashlane
        com.beemdevelopment.aegis com.kunzisoft.keepass.free com.kunzisoft.keepass.pro
        com.yubico.yubioath proton.android.pass org.malwarebytes.antimalware
        com.avast.android.mobilesecurity com.symantec.mobilesecurity com.wsandroid.suite
        com.bitdefender.security com.kms.free
    """,
    AppCategory.Tools to """
        com.google.android.calculator com.google.android.deskclock com.google.android.apps.nbu.files
        com.google.android.inputmethod.latin com.android.settings
        com.google.android.apps.recorder com.google.android.apps.subscriptions.red com.android.vending
        com.google.android.apps.wellbeing com.google.android.documentsui com.google.android.apps.adm
        com.google.android.apps.googleassistant com.google.ar.lens com.sec.android.app.popupcalculator
        com.sec.android.app.clockpackage com.sec.android.app.myfiles
    """,
)
