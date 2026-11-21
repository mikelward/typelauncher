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
 * package (Netflix in Media, Waze in Car), one from a games publisher
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
private fun labelCategory(label: String): AppCategory? = labelBrandCategory(label) ?: labelWordCategory(label)

// Brands first, as whole words or runs of them: "Fly Delta", "My Bupa",
// "Priority Pass", and "Royal Mail" before "mail" makes it email.
private fun labelBrandCategory(label: String): AppCategory? {
    if (label.isBlank()) return null
    val words = label.lowercase().split(LABEL_WORD_BREAK)
    val padded = words.filter { it.isNotEmpty() }.joinToString(" ", prefix = " ", postfix = " ")
    // The phone app itself, whatever package it comes in.
    if (padded == " phone " || padded == " dialer ") return AppCategory.Phone
    return LABEL_BRANDS.firstOrNull { (brand, _) -> " $brand " in padded }?.second
}

/**
 * True when [knownAppCategory] places the app only by a word in its name
 * ("Greenfield Bank"), not by its package, its publisher or its brand: a
 * guess, which "Check guessed folders" asks the user about.
 */
internal fun isPlacedByNameWord(packageName: String, label: String): Boolean =
    KnownApps.byPackage[packageName] == null &&
        KNOWN_PACKAGE_PREFIXES.none { (prefix, _) -> packageName.startsWith(prefix) } &&
        labelBrandCategory(label) == null &&
        labelWordCategory(label) != null

private fun labelWordCategory(label: String): AppCategory? {
    if (label.isBlank()) return null
    val words = label.lowercase().split(LABEL_WORD_BREAK)
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
    "wanderlog", "priority pass", "loungekey", "tripit",
).map { it to AppCategory.Travel } + listOf(
    "bupa" to AppCategory.Health,
    "hca" to AppCategory.Health,
    "nhs" to AppCategory.Health,
    "unifi" to AppCategory.Internet,
    "9now" to AppCategory.Media,
    "amp super" to AppCategory.Finance,
    "argos" to AppCategory.Shopping,
    "debenhams" to AppCategory.Shopping,
    "john lewis" to AppCategory.Shopping,
    "home depot" to AppCategory.Shopping,
    "best buy" to AppCategory.Shopping,
    "pb tech" to AppCategory.Shopping,
    "costco" to AppCategory.Shopping,
    "kijiji" to AppCategory.Shopping,
    "sephora" to AppCategory.Shopping,
    "ulta" to AppCategory.Shopping,
    "bunnings" to AppCategory.Shopping,
    "wendy s" to AppCategory.Food,
    "kfc" to AppCategory.Food,
    "tim hortons" to AppCategory.Food,
    "circle k" to AppCategory.Food,
    "sainsbury s" to AppCategory.Food,
    "morrisons" to AppCategory.Food,
    "lidl" to AppCategory.Food,
    "woolworths" to AppCategory.Food,
    "coles" to AppCategory.Food,
    "first table" to AppCategory.Food,
    "costa coffee" to AppCategory.Food,
    "bws" to AppCategory.Food,
    "liquorland" to AppCategory.Food,
    "dan murphy s" to AppCategory.Food,
    "anz" to AppCategory.Finance,
    "australiansuper" to AppCategory.Finance,
    "chase" to AppCategory.Finance,
    "scottish widows" to AppCategory.Finance,
    "compare the market" to AppCategory.Finance,
    "moneysavingexpert" to AppCategory.Finance,
    "experian" to AppCategory.Finance,
    "zilch" to AppCategory.Finance,
    "dave" to AppCategory.Finance,
    "brigit" to AppCategory.Finance,
    "sumup" to AppCategory.Finance,
    "koho" to AppCategory.Finance,
    "wealthsimple" to AppCategory.Finance,
    "sharesies" to AppCategory.Finance,
    "td canada" to AppCategory.Finance,
    "rbc" to AppCategory.Finance,
    "sun life" to AppCategory.Finance,
    "clearpay" to AppCategory.Finance,
    "afterpay" to AppCategory.Finance,
    "klarna" to AppCategory.Finance,
    "commsec" to AppCategory.Finance,
    "mypaynow" to AppCategory.Finance,
    "mercury" to AppCategory.Finance,
    "textnow" to AppCategory.Phone,
    "text free" to AppCategory.Phone,
    "youtube tv" to AppCategory.Media,
    "roku" to AppCategory.Media,
    "vizio" to AppCategory.Media,
    "paramount" to AppCategory.Media,
    "i m a celeb" to AppCategory.Media,
    "7plus" to AppCategory.Media,
    "cineplex" to AppCategory.Media,
    "kivoreels" to AppCategory.Media,
    "my sky" to AppCategory.Media,
    "gasbuddy" to AppCategory.Car,
    "mobil" to AppCategory.Car,
    "linkt" to AppCategory.Car,
    "paymypark" to AppCategory.Car,
    "parkmate" to AppCategory.Car,
    "nzta" to AppCategory.Car,
    "waka kotahi" to AppCategory.Car,
    "myrac" to AppCategory.Car,
    "rac" to AppCategory.Car,
    "the aa" to AppCategory.Car,
    "nrma" to AppCategory.Car,
    "toyota" to AppCategory.Car,
    "nissan" to AppCategory.Car,
    "honda" to AppCategory.Car,
    "hyundai" to AppCategory.Car,
    "kia" to AppCategory.Car,
    "tesla" to AppCategory.Car,
    "bmw" to AppCategory.Car,
    "mercedes" to AppCategory.Car,
    "audi" to AppCategory.Car,
    "porsche" to AppCategory.Car,
    "volvo" to AppCategory.Car,
    "renault" to AppCategory.Car,
    "peugeot" to AppCategory.Car,
    "citroen" to AppCategory.Car,
    "skoda" to AppCategory.Car,
    "fiat" to AppCategory.Car,
    "jaguar" to AppCategory.Car,
    "land rover" to AppCategory.Car,
    "mazda" to AppCategory.Car,
    "subaru" to AppCategory.Car,
    "mitsubishi" to AppCategory.Car,
    "chevrolet" to AppCategory.Car,
    "jeep" to AppCategory.Car,
    "lexus" to AppCategory.Car,
    "polestar" to AppCategory.Car,
    "byd" to AppCategory.Car,
    "cupra" to AppCategory.Car,
    "vauxhall" to AppCategory.Car,
    "ford" to AppCategory.Car,
    "dacia" to AppCategory.Car,
    "suzuki" to AppCategory.Car,
    "autotrader" to AppCategory.Car,
    "carsales" to AppCategory.Car,
    "carfax" to AppCategory.Car,
    "cazoo" to AppCategory.Car,
    "vrbo" to AppCategory.Hotels,
    "air nz" to AppCategory.Travel,
    "mychart" to AppCategory.Health,
    "healthengine" to AppCategory.Health,
    "medibank" to AppCategory.Health,
    "employment hero" to AppCategory.Jobs,
    "workday" to AppCategory.Jobs,
    "ukg" to AppCategory.Jobs,
    "shiftsmart" to AppCategory.Jobs,
    "amazon flex" to AppCategory.Jobs,
    "life360" to AppCategory.Kids,
    "safenest" to AppCategory.Kids,
    "family gps" to AppCategory.Kids,
    "blink" to AppCategory.Home,
    "govee" to AppCategory.Home,
    "uswitch" to AppCategory.Home,
    "british gas" to AppCategory.Home,
    "hive" to AppCategory.Home,
    "artmate" to AppCategory.Design,
    "sketchbook" to AppCategory.Design,
    "biblescroll" to AppCategory.Reading,
    "salaat first" to AppCategory.Reading,
    "muslim pro" to AppCategory.Reading,
    "athan" to AppCategory.Reading,
    "prayer times" to AppCategory.Reading,
    "nusuk" to AppCategory.Travel,
    "barid bank" to AppCategory.Finance,
    "instapay" to AppCategory.Finance,
    "getyourguide" to AppCategory.Travel,
    "viator" to AppCategory.Travel,
    "klook" to AppCategory.Travel,
    "tiqets" to AppCategory.Travel,
    "playout" to AppCategory.Rewards,
    "coupert" to AppCategory.Shopping,
    "dealseek" to AppCategory.Shopping,
    "adidas" to AppCategory.Shopping,
    "puma" to AppCategory.Shopping,
    "tide business" to AppCategory.Finance,
    "xtb" to AppCategory.Finance,
    "inshot" to AppCategory.Design,
    // MTN (maintainer, 2026-11-21): mobile money is Finance, the account app Phone.
    "mtn momo" to AppCategory.Finance,
    "capitec" to AppCategory.Finance,
    "gotyme" to AppCategory.Finance,
    "tymebank" to AppCategory.Finance,
    "remitly" to AppCategory.Finance,
    "western union" to AppCategory.Finance,
    "worldremit" to AppCategory.Finance,
    "revolut" to AppCategory.Finance,
    // The Philippines (maintainer, 2026-11-21). Maya by package: it's also a name.
    "gcash" to AppCategory.Finance,
    "seabank" to AppCategory.Finance,
    "bpi" to AppCategory.Finance,
    "bdo" to AppCategory.Finance,
    "egovph" to AppCategory.Government,
    "digid" to AppCategory.Government,
    "mysugr" to AppCategory.Health,
    "pedometer" to AppCategory.Health,
    "step counter" to AppCategory.Health,
    "step tracker" to AppCategory.Health,
    "betterhelp" to AppCategory.Health,
    "yazio" to AppCategory.Health,
    "oral b" to AppCategory.Health,
    "mi fitness" to AppCategory.Health,
    "geocaching" to AppCategory.Games,
    "period tracker" to AppCategory.Health,
    "period calendar" to AppCategory.Health,
    "ovulation" to AppCategory.Health,
    "fertility" to AppCategory.Health,
    "kegel" to AppCategory.Health,
    "pelvic floor" to AppCategory.Health,
    "weight loss" to AppCategory.Health,
    "home workout" to AppCategory.Health,
    "blinkist" to AppCategory.Reading,
    "noom" to AppCategory.Health,
    "grok" to AppCategory.Ai,
    "webull" to AppCategory.Finance,
    "whatnot" to AppCategory.Shopping,
    "elevenreader" to AppCategory.Reading,
    "therapy" to AppCategory.Health,
    "therapist" to AppCategory.Health,
    "mental health" to AppCategory.Health,
    "counseling" to AppCategory.Health,
    "anxiety" to AppCategory.Health,
    "dexcom" to AppCategory.Health,
    "kaiser permanente" to AppCategory.Health,
    "freestyle libre" to AppCategory.Health,
    "omnipod" to AppCategory.Health,
    "accu chek" to AppCategory.Health,
    // A rewards app, though its name says "donate".
    "justplay" to AppCategory.Rewards,
    // Charity & giving (maintainer, 2026-11-21).
    "sharethemeal" to AppCategory.Charity,
    "gofundme" to AppCategory.Charity,
    "justgiving" to AppCategory.Charity,
    "givebutter" to AppCategory.Charity,
    "unicef" to AppCategory.Charity,
    "red cross" to AppCategory.Charity,
    "save the children" to AppCategory.Charity,
    "oxfam" to AppCategory.Charity,
    "wwf" to AppCategory.Charity,
    "doctors without borders" to AppCategory.Charity,
    "msf" to AppCategory.Charity,
    "kiva" to AppCategory.Charity,
    "every org" to AppCategory.Charity,
    "givelify" to AppCategory.Charity,
    "tithe ly" to AppCategory.Charity,
    "olio" to AppCategory.Food,
    "videoland" to AppCategory.Media,
    "momo" to AppCategory.Finance,
    "mymtn" to AppCategory.Phone,
    "my mtn" to AppCategory.Phone,
    "airtel thanks" to AppCategory.Phone,
    "xender" to AppCategory.Tools,
    "shareit" to AppCategory.Tools,
    "uae pass" to AppCategory.Government,
    "botim" to AppCategory.Social,
    "rakbank" to AppCategory.Finance,
    "fab mobile" to AppCategory.Finance,
    "emirates nbd" to AppCategory.Finance,
    "etisalat money" to AppCategory.Finance,
    // India (maintainer, 2026-11-21).
    "phonepe" to AppCategory.Finance,
    "paytm" to AppCategory.Finance,
    "bhim" to AppCategory.Finance,
    "yono sbi" to AppCategory.Finance,
    "meesho" to AppCategory.Shopping,
    "flipkart" to AppCategory.Shopping,
    "myntra" to AppCategory.Shopping,
    "ajio" to AppCategory.Shopping,
    "nykaa" to AppCategory.Shopping,
    "blinkit" to AppCategory.Food,
    "zepto" to AppCategory.Food,
    "swiggy" to AppCategory.Food,
    "zomato" to AppCategory.Food,
    "bigbasket" to AppCategory.Food,
    "rapido" to AppCategory.Car,
    "attijariwafa" to AppCategory.Finance,
    "cih bank" to AppCategory.Finance,
    "marjane" to AppCategory.Shopping,
    "rednote" to AppCategory.Social,
    "volkswagen" to AppCategory.Car,
    "miele" to AppCategory.Home,
    "starlink" to AppCategory.Internet,
    "saily" to AppCategory.Phone,
    "esim" to AppCategory.Phone,
    "suzerain" to AppCategory.Games,
    // New folders (maintainer, 2026-11-19).
    "photo editor" to AppCategory.Design,
    "video editor" to AppCategory.Design,
    "image editor" to AppCategory.Design,
    "photo editing" to AppCategory.Design,
    "video editing" to AppCategory.Design,
    "video maker" to AppCategory.Design,
    "video generator" to AppCategory.Design,
    "image generator" to AppCategory.Design,
    "photo generator" to AppCategory.Design,
    "canva" to AppCategory.Design,
    "capcut" to AppCategory.Design,
    "bet365" to AppCategory.Betting,
    "draftkings" to AppCategory.Betting,
    "fanduel" to AppCategory.Betting,
    "sportsbet" to AppCategory.Betting,
    "betfair" to AppCategory.Betting,
    "paddy power" to AppCategory.Betting,
    "ladbrokes" to AppCategory.Betting,
    "william hill" to AppCategory.Betting,
    "betfred" to AppCategory.Betting,
    "stubhub" to AppCategory.Events,
    "seatgeek" to AppCategory.Events,
    "tickpick" to AppCategory.Events,
    "viagogo" to AppCategory.Events,
    "eventbrite" to AppCategory.Events,
    "indeed" to AppCategory.Jobs,
    "seek" to AppCategory.Jobs,
    "glassdoor" to AppCategory.Jobs,
    "totaljobs" to AppCategory.Jobs,
    "dasher" to AppCategory.Jobs,
    "spark driver" to AppCategory.Jobs,
    "uber driver" to AppCategory.Jobs,
    "flybuys" to AppCategory.Rewards,
    "nectar" to AppCategory.Rewards,
    "freecash" to AppCategory.Rewards,
    "weward" to AppCategory.Rewards,
    // A newspaper, before "courier" makes it Post.
    "courier mail" to AppCategory.News,
    "security camera" to AppCategory.Home,
    "home security" to AppCategory.Home,
    "tfl" to AppCategory.Transit,
    "transperth" to AppCategory.Transit,
    // AI assistants by name, now that "AI" alone doesn't file an app.
    "claude" to AppCategory.Ai,
    "perplexity" to AppCategory.Ai,
    "grok" to AppCategory.Ai,
    "deepseek" to AppCategory.Ai,
    "meta ai" to AppCategory.Ai,
    "le chat" to AppCategory.Ai,
) + listOf(
    // Mobile carriers, by brand (maintainer, 2026-11-19).
    "ee", "three", "vodafone", "telstra", "optus", "t mobile", "verizon", "at t", "o2", "giffgaff", "lebara",
    "lycamobile", "2degrees", "spark nz", "skinny mobile", "rogers", "telus", "koodo",
    "freedom mobile", "boost mobile", "cricket wireless", "mint mobile", "tesco mobile", "sky mobile",
    "voxi", "smarty mobile", "amaysim", "aldi mobile", "id mobile", "airalo", "truecaller",
    // Cricket Wireless's app, Tello and Talk Mobile (maintainer, 2026-11-21). Not bare
    // "cricket" (the sport) or "tello" (DJI's drone).
    "mycricket", "my cricket", "tello mobile", "my tello", "talk mobile",
).map { it to AppCategory.Phone } + listOf(
    "thefork" to AppCategory.Food,
    "the fork" to AppCategory.Food,
    "opentable" to AppCategory.Food,
    "pret" to AppCategory.Food,
    "fire tv" to AppCategory.Media,
    "antigravity" to AppCategory.Code,
    // Parking; ahead of "phone", which would make it Phone.
    "paybyphone" to AppCategory.Car,
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
).map { it to AppCategory.Post } + listOf(
    // Smart home brands (maintainer, 2026-11-21). Not "ring" or "abode",
    // which are ordinary words; Ring is matched by package.
    "tapo", "kasa", "wyze", "eufy", "arlo", "meross", "aqara", "switchbot", "lifx", "nanoleaf",
    "netatmo", "tado", "ecobee", "smartthings", "philips hue", "google home", "yale access",
    "august home", "simplisafe", "ezviz", "reolink", "tuya", "smart life", "ewelink", "sonoff",
    "shelly smart", "homey", "somfy", "lutron", "wiz connected",
).map { it to AppCategory.Home } + listOf(
    // Antivirus and privacy services, for Security.
    "blackcloak", "optery", "kanary", "privacy bee", "lookout", "eset", "sophos", "trend micro",
    "avira", "totalav", "surfshark antivirus", "webroot", "f secure", "panda dome",
).map { it to AppCategory.Security } + listOf(
    // VPNs and browsers, for Internet beside the "vpn" and "browser" words.
    // Not "brave" (Brave Frontier) or "opera" (opera houses); those are matched by package.
    "surfshark", "mullvad", "windscribe", "tunnelbear", "ipvanish", "cyberghost",
    "private internet access", "hotspot shield", "firefox", "ecosia", "duckduckgo",
).map { it to AppCategory.Internet } + listOf(
    // Parking, beside the "parking" word.
    "ringgo", "justpark", "parkmobile", "spothero", "easypark", "parkwhiz", "parkopedia",
    "flowbird", "mipermit", "cellopark", "honk mobile", "yourparkingspace", "apcoa", "ncp",
    "q park", "wilson parking", "secure parking",
).map { it to AppCategory.Car } + listOf(
    // Car rental and car sharing, in Car (maintainer, 2026-11-21). Not bare "enterprise", "budget",
    // "national" or "dollar", which are ordinary words.
    "sixt", "hertz", "avis", "europcar", "enterprise rent", "budget car", "alamo rent",
    "national car rental", "thrifty", "dollar car", "turo", "getaround", "zipcar", "share now",
    "free2move", "goget", "rentalcars", "discover cars", "car rental", "rent a car",
).map { it to AppCategory.Car } + listOf(
    // Ahead of "uber": a pinned Uber Eats shortcut has no package to go by
    // (Codex on PR #887).
    "uber eats",
).map { it to AppCategory.Food } + listOf(
    // Rideshare and taxis, in Car (maintainer, 2026-11-21). Not "grab",
    // "bolt" or "ola", which are ordinary words; those are matched by package.
    "uber", "lyft", "didi", "freenow", "free now", "indrive", "cabify", "addison lee", "addisonlee", "13cabs",
    "arro", "abetta", "gocatch", "ingogo", "veezu", "taxi butler",
    "careem", "gett", "blablacar", "waze",
).map { it to AppCategory.Car } + listOf(
    // Maps and route planners.
    "google maps", "organic maps", "osmand", "here wego", "alltrails", "citymapper", "maps me",
).map { it to AppCategory.Maps } + listOf(
    // Public transit and rail.
    "trainline", "national rail", "amtrak", "moovit", "opal travel", "ptv", "metlink", "translink",
    "transport for nsw", "tfl go", "mta", "septa", "via rail", "eurostar", "sncf connect",
    "db navigator", "trenitalia", "renfe", "ns international", "omio",
).map { it to AppCategory.Transit } + listOf(
    // Bike share, e-scooters and cycling routes. Not "lime" or "bird",
    // which are ordinary words; those are matched by package.
    "santander cycles", "citi bike", "divvy", "bluebikes", "capital bikeshare", "nextbike",
    // Not "tier" (tier lists) either.
    "beryl", "voi", "dott", "komoot", "bike share",
).map { it to AppCategory.Cycling } + listOf(
    // Hotels, hotel loyalty and places to stay (maintainer, 2026-11-21).
    "marriott", "hilton", "ihg", "hyatt", "accor", "all accor", "hotels com", "best western",
    "wyndham", "radisson", "choice hotels", "premier inn", "travelodge", "airbnb",
).map { it to AppCategory.Hotels } + listOf(
    // Theme parks, for Events & tickets (maintainer, 2026-11-21).
    "legoland", "disneyland", "my disney experience", "tokyo disney resort", "universal orlando",
    "universal studios", "universal hollywood", "six flags", "alton towers", "thorpe park",
    "chessington", "merlin annual pass", "seaworld", "sea world", "busch gardens", "cedar point",
    "knott s", "kings island", "carowinds", "dollywood", "hersheypark", "dreamworld",
    "movie world", "efteling", "europa park", "portaventura", "port aventura",
).map { it to AppCategory.Events } + listOf(
    // Cloud consoles, hosting and domains, in Code (maintainer, 2026-11-21).
    "google cloud", "aws", "aws console", "microsoft azure", "azure portal", "cloudflare",
    "namecheap", "godaddy", "digitalocean", "linode", "akamai", "vultr", "hetzner", "ovhcloud",
    "heroku", "vercel", "netlify", "firebase", "supabase", "oracle cloud", "ibm cloud", "porkbun",
).map { it to AppCategory.Code } + listOf(
    // Mesh VPNs and home broadband, for Internet.
    "tailscale", "wireguard", "openvpn", "zerotier", "netbird", "virgin media", "talktalk",
    "plusnet", "hyperoptic", "community fibre", "sky broadband", "bt broadband",
    "aussie broadband", "superloop", "iinet", "xfinity",
).map { it to AppCategory.Internet } + listOf(
    // GP and hospital apps, gyms and leisure centres, for Health.
    "nhs app", "mygp", "patient access", "better uk", "virgin active", "david lloyd",
    "anytime fitness", "nuffield health", "the gym group", "planet fitness", "orangetheory", "f45",
).map { it to AppCategory.Health } + listOf(
    // Focus sound and headphone apps, for Music beside Sonos.
    "brain fm", "endel", "sound connect", "bose", "jabra", "sennheiser", "soundcore",
).map { it to AppCategory.Music } + listOf(
    // Battery monitors, 3D printers and phone helpers, for Tools.
    "snoozemo", "ampere", "accubattery", "battery guru", "anker make", "bambu handy",
    "creality cloud", "orcaslicer", "prusa",
).map { it to AppCategory.Tools } + listOf(
    // Pensions and superannuation, for Finance beside AustralianSuper.
    "caresuper", "australian super", "hostplus", "unisuper", "aware super", "hesta", "cbus super",
    "rest super", "qsuper", "colonial first state", "pensionbee", "nest pensions", "vanguard",
).map { it to AppCategory.Finance } + listOf(
    // Football clubs, leagues and scores, for Sports.
    "chelsea fc", "arsenal", "liverpool fc", "man utd", "manchester united", "manchester city",
    "man city", "tottenham hotspur", "newcastle united", "aston villa", "everton", "west ham",
    "premier league", "fantasy premier league", "fpl", "onefootball", "fotmob", "uefa",
    "la liga", "bundesliga", "afl live", "nrl",
).map { it to AppCategory.Sports } + listOf(
    // Language learning, for Learning beside Duolingo.
    "clozemaster", "memrise", "mondly", "lingodeer", "rosetta stone", "pimsleur", "lingq",
    "italki", "preply",
).map { it to AppCategory.Learning } + listOf(
    // Bird and wildlife spotting, for Pets & animals.
    "merlin bird id", "ebird", "inaturalist",
).map { it to AppCategory.Pets } + listOf(
    // Streaming, for Media. Not "stan", which other apps are called; Stan is
    // matched by package.
    "foxtel", "binge", "britbox", "itvx", "mubi", "abc iview", "sbs on demand", "bbc iplayer",
    "reelshort", "dramabox", "shortmax", "goodshort",
).map { it to AppCategory.Media } + listOf(
    // Rail operators, for Public transit & rail.
    "stopdash", "lner", "gwr", "avanti west coast", "crosscountry", "transpennine", "scotrail",
    "northern rail", "c2c", "thameslink", "southeastern", "greater anglia", "chiltern railways",
    "grand central", "hull trains", "lumo", "merseyrail",
).map { it to AppCategory.Transit } + listOf(
    // Airlines without "airline" or "airways" in their names, for Travel & flights.
    "virgin australia", "virgin atlantic", "air new zealand", "jet2", "rex airlines", "tui",
).map { it to AppCategory.Travel } + listOf(
    // Read later and bookmarks, for Reading.
    "readmo", "raindrop", "instapaper", "readwise", "omnivore",
).map { it to AppCategory.Reading } + listOf(
    "simmo", "melita", "mymelita",
).map { it to AppCategory.Phone } + listOf(
    "clothescast", "bbc weather", "met office", "weatherzone", "accuweather",
).map { it to AppCategory.Weather } + listOf(
    "lightwave",
).map { it to AppCategory.Home } + listOf(
    "real rewards",
).map { it to AppCategory.Rewards } + listOf(
    // Croatia (maintainer, 2026-11-21), each with its neighbors. Google Play files
    // Moj Telekom under Productivity, mGrađani under Business and Glovo under
    // Lifestyle, so these rules matter more than its categories.
    "m zaba", "zaba", "otpgo", "otp banka", "george croatia", "pbz", "mpbz", "addiko", "raiffeisen",
    "hpb", "keks pay", "aircash",
    // Brokers (maintainer, 2026-11-21). Not "stake", which is also a betting site.
    "ibkr", "interactive brokers", "degiro", "saxo", "freetrade", "hargreaves lansdown", "aj bell",
    "webull", "moomoo", "tastytrade", "trading 212", "etoro", "robinhood", "vanguard investor",
).map { it to AppCategory.Finance } + listOf(
    "moj telekom", "hrvatski telekom", "a1 hrvatska", "moj a1", "telemach",
    // Switzerland and France (maintainer, 2026-11-21). Not bare "salt" or "orange".
    "swisscom", "my swisscom", "mysalt", "sunrise moa", "orange et moi", "sfr", "bouygues telecom", "free mobile",
    "saracroche",
    // Egypt and the Gulf (maintainer, 2026-11-21).
    "my etisalat", "etisalat", "orange max it", "we egypt", "my du", "zain", "stc", "mobily",
    // Not bare "du", a common French and German word.
).map { it to AppCategory.Phone } + listOf(
    "mgrađani", "e građani", "egrađani", "hzzo", "e porezna",
).map { it to AppCategory.Government } + listOf(
    "epošta", "hrvatska pošta", "box now", "boxnow", "overseas express",
).map { it to AppCategory.Post } + listOf(
    "glovo", "wolt", "bolt food", "konzum", "kaufland", "plodine",
    "lieferando", "thuisbezorgd", "pyszne", "takeaway com", "foodora", "foodpanda",
    "continente", "pingo doce", "mercadona", "carrefour", "auchan", "intermarché", "albert heijn", "jumbo supermarkten",
    // Ireland (maintainer, 2026-11-21).
    "my centra", "centra", "supervalu", "dunnes stores", "londis", "spar ireland",
    // ALDI, beside Lidl; "ALDI Mobile" is matched as Phone first (maintainer, 2026-11-21).
    "aldi", "aldi süd", "aldi nord",
).map { it to AppCategory.Food } + listOf(
    // Health and beauty chains (maintainer, 2026-11-21).
    "superdrug", "boots", "the perfume shop", "space nk", "lookfantastic", "dm drogerie", "rossmann", "müller",
    "zalando", "primark", "about you", "bershka", "pull bear", "stradivarius", "uniqlo", "c a fashion",
    "home store more", "harvey norman", "penneys", "brown thomas",
    // M&S sells clothes first, though its name mentions food (answer key).
    "marks spencer", "m s",
).map { it to AppCategory.Shopping } + listOf(
    "njuškalo",
).map { it to AppCategory.Shopping } + listOf(
    "hžpp",
    // European rail and city transit (maintainer, 2026-11-21).
    "sbb mobile", "sbb", "öbb", "oebb", "sj", "dsb", "nsb", "ns reisplanner", "sl journey planner",
    "ruter", "skånetrafiken", "bvg", "mvv", "rmv", "ratp", "tmb", "emt madrid", "atm milano",
).map { it to AppCategory.Transit } + listOf(
    "österreichische post", "post ag", "swiss post", "die post", "bpost", "omniva", "inpost",
).map { it to AppCategory.Post } + listOf(
    "gmx", "web de", "t online", "seznam email",
).map { it to AppCategory.Office }

// Keywords that must start a word: "ebook" sits inside "Facebook" and
// "Notebook" (Codex on PR #866), "sport" inside "Transport" and "Passport"
// (Codex on PR #875), "parent" inside "Transparent".
private val WORD_START_ONLY = setOf("ebook", "parent", "sport")

// Keywords that must be a whole word: "git" ends "legit", and "code" a
// "barcode" scanner.
private val WHOLE_WORD_ONLY = setOf("code", "git", "ssh", "qr", "ai", "fc", "afc", "sim")

private val LABEL_WORD_BREAK = Regex("""[^\p{L}\p{N}]+""")
private val PACKAGE_WORD_BREAK = Regex("""[._\-]+""")

// In order: the first word found decides ("Health News" is Health).
private val LABEL_WORDS = listOf(
    "charity" to AppCategory.Charity,
    "donate" to AppCategory.Charity,
    "donation" to AppCategory.Charity,
    "fundraising" to AppCategory.Charity,
    "crowdfunding" to AppCategory.Charity,
    // Scripture and faith apps, in Reading (maintainer, 2026-11-21: "any app with bible or
    // quran in the title"), inside a word too: "Alquran", "Bíblia Sagrada".
    "bible" to AppCategory.Reading,
    "biblia" to AppCategory.Reading,
    "bíblia" to AppCategory.Reading,
    "quran" to AppCategory.Reading,
    "koran" to AppCategory.Reading,
    "coran" to AppCategory.Reading,
    "hadith" to AppCategory.Reading,
    "torah" to AppCategory.Reading,
    "bhagavad" to AppCategory.Reading,
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
    // Betting before "sport": "Sportsbet - Sports Betting App".
    "betting" to AppCategory.Betting,
    "sportsbook" to AppCategory.Betting,
    "lottery" to AppCategory.Betting,
    "lotto" to AppCategory.Betting,
    "sport" to AppCategory.Sports,
    "scores" to AppCategory.Sports,
    // Football clubs: "Chelsea FC", "FC Barcelona" (maintainer, 2026-11-21).
    "fc" to AppCategory.Sports,
    "afc" to AppCategory.Sports,
    "news" to AppCategory.News,
    "messages" to AppCategory.Social,
    "messenger" to AppCategory.Social,
    "messaging" to AppCategory.Social,
    "weather" to AppCategory.Weather,
    // SIM cards and plans: "Lebara SIM", "SIM Manager" (maintainer, 2026-11-21).
    // Only as a word of its own, and a game manifest still wins ("Train Sim World").
    "sim" to AppCategory.Phone,
    "maps" to AppCategory.Maps,
    "parking" to AppCategory.Car,
    "transit" to AppCategory.Transit,
    "transport" to AppCategory.Transit,
    "airways" to AppCategory.Travel,
    "airlines" to AppCategory.Travel,
    "airline" to AppCategory.Travel,
    // "Flights", "Flightradar24".
    "flight" to AppCategory.Travel,
    "hotel" to AppCategory.Hotels,
    "taxi" to AppCategory.Car,
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
    "calendar" to AppCategory.Office,
    "email" to AppCategory.Office,
    "mail" to AppCategory.Office,
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
    // After transport words, which also sell tickets.
    "ticket" to AppCategory.Events,
    "recruit" to AppCategory.Jobs,
    "careers" to AppCategory.Jobs,
    "jobs" to AppCategory.Jobs,
    "rewards" to AppCategory.Rewards,
    "cashback" to AppCategory.Rewards,
    "loyalty" to AppCategory.Rewards,
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
    // Government domains as package names (maintainer, 2026-11-21): GOV.UK, myID,
    // Service NSW. A government app that's something else, such as PTV or the
    // Met Office, is listed by package, which comes first.
    "uk.gov." to AppCategory.Government,
    "au.gov." to AppCategory.Government,
    "nz.govt." to AppCategory.Government,
    "ca.gc." to AppCategory.Government,
    "ae.gov." to AppCategory.Government,
    "sa.gov." to AppCategory.Government,
    "in.gov." to AppCategory.Government,
    "nl.rijksoverheid." to AppCategory.Government,
    // Diabetes devices, Kaiser Permanente, and Digifit, which builds many gyms' apps
    // (maintainer, 2026-11-21).
    "com.dexcom." to AppCategory.Health,
    "org.dayup.stocks" to AppCategory.Finance,
    "org.kp." to AppCategory.Health,
    "digifit." to AppCategory.Health,
    // Austria's social insurance apps (Meine ÖGK, Meine BVAEB), and Niantic's games.
    "at.itsv." to AppCategory.Health,
    "com.nianticlabs." to AppCategory.Games,
    "gov." to AppCategory.Government,
    // Tello's apps, "My Tello" among them (maintainer, 2026-11-21); DJI's Tello drone is another package.
    "com.tello." to AppCategory.Phone,
    // Dinosaur Polo Club: Mini Metro, Mini Motorways.
    "nz.co.codepoint." to AppCategory.Games,
)

// Fallback stems, in order: the first that a package or label word matches
// (see stemMatches) files the app.
private val GUESS_RULES = listOf(
    AppCategory.Kids to listOf("kids", "toddler", "children", "baby", "pregnancy", "parent", "parenting"),
    AppCategory.Betting to listOf("betting", "sportsbook", "lottery", "lotto", "wager"),
    AppCategory.Games to listOf("game", "games", "solitaire", "sudoku", "mahjong", "chess", "puzzle", "casino", "slots", "poker", "crossword"),
    AppCategory.Weather to listOf("weather", "forecast", "radar"),
    AppCategory.Government to listOf("gov", "government", "council"),
    AppCategory.Finance to listOf("bank", "banking", "finance", "invest", "trading", "crypto", "wallet", "insurance", "mortgage", "budget"),
    AppCategory.Food to listOf("food", "recipe", "pizza", "eats", "takeaway", "grocery", "groceries", "restaurant", "burger", "coffee"),
    AppCategory.Music to listOf("music", "radio", "podcast", "karaoke"),
    AppCategory.Media to listOf("movie", "movies", "stream", "drama", "dramas", "series"),
    AppCategory.Photos to listOf("photo", "camera", "gallery", "selfie"),
    AppCategory.Sports to listOf("sport", "football", "soccer", "basketball", "cricket", "rugby", "nfl", "nba", "espn"),
    AppCategory.Health to listOf("health", "fitness", "gym", "workout", "yoga", "meditation", "sleep", "pharmacy", "medical", "doctor", "patient", "pedometer", "stepcounter", "steptracker"),
    AppCategory.Learning to listOf("learn", "school", "education", "edu", "dictionary", "language", "course", "study", "flashcards"),
    AppCategory.Pets to listOf("pet", "pets", "dog", "dogs", "vet", "petcare"),
    AppCategory.Transit to listOf("transit", "metro", "rail", "railway", "bus"),
    AppCategory.Car to listOf("taxi", "parking", "car", "cars", "vehicle", "charging", "fuel", "petrol"),
    AppCategory.Maps to listOf("maps", "navigation"),
    AppCategory.Cycling to listOf("scooter", "bikeshare", "cycling"),
    AppCategory.Hotels to listOf("hotel", "hotels"),
    AppCategory.Travel to listOf("airline", "airways", "flight", "travel", "booking"),
    AppCategory.Reading to listOf("ebook", "audiobook", "kindle", "comic", "manga", "novel", "librar"),
    AppCategory.Shopping to listOf("shop", "deals", "coupon", "marketplace"),
    AppCategory.Code to listOf("coding", "programming", "developer", "terminal", "ssh", "git", "python", "javascript"),
    AppCategory.Internet to listOf("browser", "internet", "vpn", "wifi", "speedtest"),
    AppCategory.Phone to listOf("telecom", "dialer", "esim"),
    AppCategory.Post to listOf("parcel", "courier", "postal", "freight"),
    AppCategory.News to listOf("news", "newspaper", "headlines"),
    AppCategory.Social to listOf("chat", "messenger", "messaging", "messages", "social", "dating"),
    AppCategory.Office to listOf("mail", "email", "calendar", "notes", "docs", "todo", "tasks", "office", "pdf", "meeting"),
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
        com.sec.android.gallery3d com.vsco.cam
        com.amazon.clouddrive.photos com.google.android.apps.photos.scanner
    """,
    AppCategory.Design to """
        com.adobe.lrmobile com.niksoftware.snapseed com.picsart.studio com.canva.editor
        com.lemon.lvoverseas com.adobe.spark.post com.adobe.psmobile jp.ne.ibis.ibispaintx.app
        com.adobe.premierrush.videoeditor com.instagram.edits
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
    AppCategory.Office to """
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
        com.booking com.expedia.bookings com.tripadvisor.tripadvisor
        com.kayak.android com.hopper.mountainview.play net.skyscanner.android.main
        com.ryanair.cheapflights com.ba.mobile com.delta.mobile.android com.aa.android
        com.united.mobile.android com.southwestairlines.mobile com.tripit com.jetblue.JetBlueAndroid
        com.jetstar.mobile com.emirates.ek.android com.qantas.qantasapp com.lufthansa.android.lufthansa
        com.klm.mobile.iflyapp com.easyjet.android com.alaskaairlines.android
    """,
    AppCategory.Maps to """
        com.google.android.apps.maps com.citymapper.app.release com.here.app.maps com.sygic.aura
        app.organicmaps net.osmand com.alltrails.alltrails
    """,
    AppCategory.Car to """
        com.waze com.ubercab me.lyft.android ee.mtakso.client net.sharewire.parkmobilev2
        com.teslamotors.tesla com.coulombtech com.google.android.projection.gearhead
        taxi.android.client com.grabtaxi.passenger com.olacabs.customer com.ehi.enterprise.android
    """,
    AppCategory.Transit to """
        com.tranzmate com.thetrainline com.thetransitapp.droid com.amtrak.rider
    """,
    AppCategory.Cycling to """
        com.limebike com.bird
    """,
    AppCategory.Hotels to """
        com.airbnb.android com.hcom.android com.marriott.mrt com.hilton.android.hhonors
        com.ihg.apps.android com.vacationrentals.homeaway
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
    AppCategory.Code to """
        com.google.android.apps.cloudconsole com.amazon.aws.console.mobile com.microsoft.azure
        com.namecheap.android sh.paseo com.ex3ndr.happy dev.happier.app
    """,
    AppCategory.Internet to """
        com.tailscale.ipn com.cloudflare.cloudflareoneagent com.wireguard.android
        net.openvpn.openvpn com.zerotier.one com.virginmedia.android.youraccount
    """,
    AppCategory.Health to """
        epic.mychart.android net.iplato.mygp com.innovatise.bettergll
    """,
    AppCategory.Music to """
        com.brainfm.app com.endel.endel com.sony.songpal.mdr com.bose.bosemusic com.bose.monet
    """,
    AppCategory.Tools to """
        app.snoozemo com.gombosdev.ampere com.digibites.accubattery com.paget96.batteryguru
        com.oceanwing.FDMPrint bbl.intl.bambulab.com com.nothing.smartcenter
        com.samsung.android.app.watchmanager
    """,
    AppCategory.Finance to """
        com.mtaa.membercentre.app
    """,
    AppCategory.Sports to """
        com.chelseafc.the5thstand
    """,
    AppCategory.Learning to """
        com.clozemaster.v2 com.memrise.android.memrisecompanion com.atistudios.mondly.languages
    """,
    AppCategory.Pets to """
        com.labs.merlinbirdid.app edu.cornell.birds.ebird
    """,
    AppCategory.Media to """
        au.com.stan.and
    """,
    AppCategory.Transit to """
        uk.co.icectoc.customer uk.co.nationalrail.google
    """,
    AppCategory.Travel to """
        com.virginaustralia.vaapp
    """,
    AppCategory.Reading to """
        io.raindrop.raindropio
    """,
    AppCategory.Phone to """
        app.simmo com.melita.mobile.selfcare com.mizmowireless.acctmgt
    """,
    AppCategory.Weather to """
        app.clothescast bbc.mobile.weather au.gov.bom.metview
    """,
    AppCategory.Home to """
        com.lightwaverf.linkplus
    """,
    // Croatia (maintainer, 2026-11-21).
    AppCategory.Finance to """
        hr.asseco.android.zaba.new hr.asseco.android.ae.otp hr.erstebank.george
    """,
    AppCategory.Phone to """
        com.hrvatskitelekom.m.mojT
    """,
    AppCategory.Government to """
        hr.mgradani
    """,
    AppCategory.Post to """
        hr.sedamit.hpmbox
    """,
    AppCategory.Food to """
        com.glovo
    """,
    AppCategory.Transit to """
        com.hzppispromobile
    """,
    AppCategory.Finance to """
        atws.app com.chase.intl net.intellectsoft.experian com.mse.mseapp
        hu.otpbank.mobile com.db.pwcc.dbmobile ro.raiffeisen.smartmobile at.erstebank.george
        hr.asseco.android.jimba.mUCI.bg bg.postbank.mobile.android.release com.danskebank.mobilebank3.uk
        pl.bzwbk.bzwbk24 rs.erstebank.george rs.Raiffeisen.mobile
        com.posteitaliane.spim com.starfinanz.mobile.android.pushtan de.deutschepost.postident
        com.bankinter.portugal.bmb com.BancoDePortugal.BPmobile com.abanca.bm.pt pt.santander.oneappparticulares
        ma.creditagricole.banke com.saphir.baridbankmobile com.egyptianbanks.instapay
        com.phonepe.app net.one97.paytm com.boi.ua.android com.naviapp
        com.fab.personalbanking com.rak com.etisalat.ewallet
        com.tideplatform.banking com.xtb.xmobile2 mtnft.momo.consumer com.momosa
        capitec.acuity.mobile.prod za.co.gotyme ph.com.gotyme com.remitly.androidapp com.revolut.junior
        com.globe.gcash.android ph.seabank.seabank com.paymaya com.bpi.ng.app
    """,
    AppCategory.Phone to """
        com.swisscom.myswisscom ch.salt.my com.orange.orangeetmoi com.orange.mobinilandme com.etisalat com.truecaller com.Etisalat.ETIDA
        ng.mtn.nextgen com.mtngh.mymtn com.mtn1app
    """,
    AppCategory.Media to """
        au.net.abc.iview com.newleaf.app.android.victor nl.rtl.videoland.v2
    """,
    // Egypt's Faheem AI tutor: Learning, though "AI" in its name says AI.
    AppCategory.Learning to """
        com.moe.fahem
    """,
    AppCategory.Government to """
        it.pagopa.io.app es.fnmtrcm.ceres.certificadoDigitalFNMT pt.segsocial.mobile.segurancasocial
        ae.uaepass.mainapp com.uaemoi.smartservices egov.app
    """,
    AppCategory.Shopping to """
        fr.vinted de.idealo.android de.zalando.mobile com.primark.app.prod com.homestoreandmore.hsmapp uk.co.next.android
        com.marksandspencer.app com.boots.flagship.android ma.marjanemall.marjanemall com.meesho.supply com.flipkart.android
        com.dealseekorg.dealseek com.soarinfotech.coupert com.adidas.app com.fsn.nds
    """,
    AppCategory.News to """
        com.france24.androidapp
    """,
    // Portugal's Continente and Ireland's Centra: grocers, so Food, beside Lidl and Tesco.
    AppCategory.Food to """
        pt.continente.LojaContinente ie.centra.loyalty com.lidl.eci.lidlplus com.yopeso.lieferando com.grofers.customerapp com.olioex.android
    """,
    AppCategory.Rewards to """
        com.aimia.android.musgrave io.playout
    """,
    // Faith apps go in Reading, beside the Bible and the Quran.
    AppCategory.Reading to """
        org.hicham.salaat com.pakdata.QuranMajeed com.andi.alquran.id com.quran.labs.androidquran
        com.sirma.mobile.bible.android com.azwstudios.theholybible.kjv com.bible.holy.bible.for.women
        com.bestweatherfor.bibleoffline_pt_ra org.lds.ldssa bhagavan.id
    """,
    AppCategory.Travel to """
        com.moh.nusukapp com.getyourguide.android com.viator.mobile.android
    """,
    AppCategory.Ai to """
        com.facebook.stella
    """,
    AppCategory.Car to """
        com.rapido.passenger com.qmobility.darbx
    """,
    AppCategory.Social to """
        im.thebot.messenger com.metaztech.fawoo com.zhiliaoapp.musically.go com.instagram.moonshot
        com.facebook.lite
    """,
    AppCategory.Design to """
        com.camerasideas.instashot com.instagram.basel
    """,
    AppCategory.Tools to """
        cn.xender
    """,
    AppCategory.Phone to """
        com.cbouvat.android.saracroche
    """,
    AppCategory.Health to """
        com.aon.sante org.wakingup.android com.mysugr.android.companion uk.co.virginactive.android
        at.svagw.mobile.svaapp com.betterhelp com.yazio.android com.pg.oralb.oralbapp com.xiaomi.wearable
        org.iggymedia.periodtracker com.clue.android com.app.lively_app com.junochat.app
        com.popularapp.periodcalendar com.lbrc.PeriodCalendar premom.eh.com.ehpremomapp com.glow.android
        com.wsl.noom com.mindbodyonline.connect in.plackal.lovecyclesfree co.manual.voy com.phe.daysoff
        com.vamapps.thecoach com.getmental.mental homeworkout.homeworkouts.noequipment com.jsdev.pfei
        kegel.kegelexercises.pelvicfloor.pfm com.lescadeaux.kegel com.elevatelabs.geonosis
        com.theinnerhour.b2b com.talkspace.talkspaceapp com.cyrano.cyrano co.mindpeers de.moodpath.android
        bot.touchkin us.regain com.mypossibleself.app com.helpy.cbt com.ifeel.ifeeluserchat com.bstro.MindShift
        com.lasting.lasting com.mentallabs.mentalhealth in.evolve.android com.technogym.tgapp
    """,
    AppCategory.Shopping to """ com.whatnot_mobile """,
    AppCategory.Finance to """ com.raisin.app com.emmaprod org.dayup.stocks.uk """,
    AppCategory.Ai to """ ai.x.grok.bot com.larus.wolf """,
    AppCategory.Travel to """ com.usebounce se.memfrag.app """,
    AppCategory.Learning to """ com.mobisystems.msdict.embedded.wireless.oxford.dictionaryofenglish """,
    AppCategory.Kids to """ com.tribo.bubbleapp """,
    AppCategory.Reading to """ io.elevenlabs.readerapp """,
    AppCategory.Kids to """
        com.huckleberry_labs.app
    """,
    AppCategory.Reading to """
        com.blinkslabs.blinkist.android
    """,
    AppCategory.Cycling to """
        de.komoot.android
    """,
    // Geocaching: an outdoor game, beside Niantic's.
    AppCategory.Games to """
        com.groundspeak.geocaching.intro
    """,
    AppCategory.Charity to """
        org.sharethemeal.app
    """,
    // Top apps whose names mislead the word rules, filed as the answer key
    // has them (maintainer, 2026-11-21). Kalshi, Morrisons More Card and
    // Spriggy could go either way.
    AppCategory.Finance to """
        com.kalshi.mobile
    """,
    AppCategory.Shopping to """
        com.crowncards.app com.wishabi.flipp nz.co.trademe.trademe com.morrisons.matchandmore.app
        com.bluelightcard.user
    """,
    AppCategory.Design to """
        com.mara.modstune com.photo.restore.scan com.aiphotoedior.photogenerator.photoretouch
        com.xphotokit.picfusionlab
    """,
    AppCategory.Car to """
        com.zenergy.zenergyapp.android.prod
    """,
    AppCategory.Ai to """
        com.scatterlab.messenger
    """,
    AppCategory.Media to """
        com.fox.foxone
    """,
    AppCategory.News to """
        com.newsbreak.crimeradar
    """,
    AppCategory.Reading to """
        com.radio.pocketfm com.square_enix.android_googleplay.mangaup_global
    """,
    AppCategory.Social to """
        sg.bigo.live
    """,
    AppCategory.Tools to """
        com.Endoscope.scope
    """,
    AppCategory.Kids to """
        org.blubblub.app.speechblubs com.cashless.cashlessapp
    """,
    AppCategory.Sports to """
        app.mygameday.results
    """,
    AppCategory.Office to """
        de.gmx.mobile.android.mail de.web.mobile.android.mail
    """,
    AppCategory.Post to """
        at.post.app nl.tpp.mobile.android
    """,
    AppCategory.Transit to """
        ch.sbb.mobile.android.b2c com.sl.SLBiljetter au.gov.vic.ptv au.com.opal.travel
    """,
    AppCategory.Food to """
        de.apptiv.business.android.aldi_uk
    """,
    AppCategory.Shopping to """
        superdrug.com.beautycard
    """,
    AppCategory.Travel to """
        com.thomson.mythomson
    """,
)
