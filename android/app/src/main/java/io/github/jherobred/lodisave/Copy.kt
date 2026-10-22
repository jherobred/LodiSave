package io.github.jherobred.lodisave

import android.content.Context
import androidx.core.content.edit

/** The app's words in English and Taglish. The language is an in-app setting, not the phone's. */
class Copy(val tl: Boolean) {
    private fun t(en: String, tl: String) = if (this.tl) tl else en

    val idle = t("Copy a link to save it", "Mag-copy ng link para i-save")
    val idleSub = t("Instagram, TikTok, X, Facebook, Reddit and more.", "Instagram, TikTok, X, Facebook, Reddit at iba pa.")
    val paste = t("Paste a link", "I-paste ang link")
    val linkCopied = t("Link copied!", "May na-copy kang link!")
    val save = t("Save", "I-save")
    val later = t("Later", "Mamaya na")
    val open = t("Open", "Buksan")
    val again = t("Try again", "Subukan ulit")
    val close = t("Close", "Isara")
    val helper = t("Getting the video helper…", "Kinukuha ang video helper…")
    val helperSub = t("One-time download of yt-dlp, about 3 MB.", "Isang beses lang i-download ang yt-dlp, mga 3 MB.")
    val reading = t("Reading the link…", "Binabasa ang link…")
    fun saving(percent: Float) = when {
        percent < 0 -> t("Saving…", "Sine-save…")
        else -> t("Saving ${percent.toInt()}%", "Sine-save ${percent.toInt()}%")
    }
    val joining = t("Joining video and audio…", "Pinagsasama ang video at audio…")
    val sharing = t("Saving what you shared…", "Sine-save ang shinare mo…")
    val saved = t("Saved!", "Na-save!")
    fun savedMany(n: Int) = t("Saved $n files!", "Na-save ang $n files!")
    fun savedSome(saved: Int, failed: Int) = t("Saved $saved, $failed didn't work", "Na-save ang $saved, $failed ang hindi gumana")
    val failed = t("That didn't work", "Hindi gumana")
    val pickTitle = t("Pick what to save", "Piliin ang ise-save")
    fun pickSub(n: Int) = t("$n photos and videos in this post", "$n na photo at video sa post na ito")
    val pickAll = t("Select all", "Piliin lahat")
    val pickNone = t("Clear", "Alisin lahat")
    fun saveN(n: Int) = t("Save $n", "I-save ang $n")
    val pick = t("Pick", "Pumili")
    val tray = t("Saving", "Sine-save")
    fun alsoSaving(n: Int) = t("$n more saving", "$n pa ang sine-save")
    val bubble = t("Floating Lodi", "Lumulutang na Lodi")
    val bubbleHint = t("Over every app: tap Copy link on a post, then tap Lodi.", "Sa ibabaw ng bawat app: i-tap ang Copy link sa post, tapos i-tap si Lodi.")
    val bubbleNote = t("Lodi is floating. Copy a link, then tap Lodi.", "Lumulutang si Lodi. Mag-copy ng link, tapos i-tap si Lodi.")
    val bubbleHide = t("Hide Lodi", "Itago si Lodi")
    val pickHint = t("Tap ○ on photos and videos", "I-tap ang ○ sa mga photo at video")
    fun pickedN(n: Int) = t("$n picked", "$n ang napili")
    val pickFetching = t("Getting the post's link…", "Kinukuha ang link ng post…")
    val pickNoLink = t("Couldn't get a link or a picture of that one", "Walang makuhang link o larawan doon")
    fun savingN(n: Int) = t("Saving $n to your gallery…", "Sine-save ang $n sa gallery…")
    val pickCopied = t("Copied link", "Na-copy na link")
    val picker = t("Pick in other apps", "Pumili sa ibang apps")
    val pickerHint = t(
        "Turn on LodiSave picker in Accessibility. Then tap the floating Lodi in Threads, Instagram, Reddit or any app, and tick the round circles on photos and videos.",
        "I-on ang LodiSave picker sa Accessibility. Tapos i-tap si Lodi sa Threads, Instagram, Reddit o kahit anong app, at i-tick ang mga bilog sa photo at video.",
    )
    val pickerRestricted = t(
        "If Android says the setting is restricted, open App info, tap ⋮, then Allow restricted settings.",
        "Kung sabihin ng Android na restricted ang setting, buksan ang App info, i-tap ang ⋮, tapos Allow restricted settings.",
    )
    val appInfo = t("App info", "App info")
    val xiaomi = t("Xiaomi: allow pop-up windows while running in the background", "Xiaomi: i-allow ang pop-up windows while running in the background")
    val openAccessibility = t("Open Accessibility", "Buksan ang Accessibility")

    /** Title and explanation for an error code from the save engine, or its own message. `site` is
     *  the account a "login" link could use, when LodiSave has one for it. */
    fun error(code: String, site: String? = null): Pair<String, String> = when (code) {
        "login" -> t("This one needs a login", "Kailangan mag-login dito") to when (site) {
            null -> t("LodiSave can save public posts only from this site.", "Pampublikong post lang ang kaya ng LodiSave sa site na ito.")
            else -> t("Sign in to $site and LodiSave can save what your account can see.", "Mag-sign in sa $site at kaya nang i-save ng LodiSave ang nakikita ng account mo.")
        }
        "nothing" -> t("Nothing to save here", "Walang ma-save dito") to
            t("LodiSave found no video or image at this link.", "Walang nakitang video o image ang LodiSave sa link na ito.")
        "offline" -> t("Can't reach the site", "Hindi ma-reach ang site") to
            t("Check your connection and try again.", "I-check ang internet mo at subukan ulit.")
        "refused" -> t("The site said no", "Ayaw ibigay ng site") to
            t("It blocks some networks and regions. Try again later or on another network.", "May mga network at lugar na hinaharang nito. Subukan ulit mamaya o sa ibang network.")
        "gone" -> t("This post is gone", "Wala na ang post na ito") to
            t("It was removed, made private, or the link is wrong.", "Tinanggal, ginawang private, o mali ang link.")
        "broken" -> t("LodiSave can't read this site right now", "Hindi pa mabasa ng LodiSave ang site na ito") to
            t("The site changed. LodiSave updates its reader every week, so try again later.", "Nagbago ang site. Linggo-linggo nag-a-update ang LodiSave, kaya subukan ulit mamaya.")
        "helper" -> t("Couldn't get the video helper", "Hindi makuha ang video helper") to
            t("LodiSave downloads yt-dlp the first time. Check your connection.", "Dina-download ng LodiSave ang yt-dlp sa unang gamit. I-check ang internet mo.")
        "noaccess" -> t("That app didn't hand it over", "Hindi ibinigay ng app") to
            t("It shared the file without letting LodiSave open it. Save it in that app, or share its link instead.", "Shinare nito ang file pero hindi pinabuksan sa LodiSave. I-save ito sa app na iyon, o i-share ang link.")
        "nolink" -> t("No link copied", "Walang na-copy na link") to
            t("Tap Share, then Copy link on the post, then tap Lodi again.", "I-tap ang Share, tapos Copy link sa post, tapos i-tap ulit si Lodi.")
        else -> failed to code
    }

    val recent = t("Recent", "Mga na-save kanina")
    val none = t("Your saves show up here.", "Dito lalabas ang mga na-save mo.")
    val settings = t("Settings", "Settings")
    val language = t("Language", "Wika")
    val motion = t("Motion", "Galaw")
    val on = "On"
    val off = "Off"
    val done = t("Done", "Sige")
    val widget = t("Home screen widget", "Widget sa home screen")
    val widgetHint = t("One tap saves the link you copied.", "Isang tap lang, na-save na ang link na na-copy mo.")
    val widgetAdd = t("Add", "Idagdag")
    val widgetLabel = t("Save link", "I-save ang link")
    val notification = t("Saving with LodiSave", "Sine-save ng LodiSave")
    fun ago(seconds: Long) = when {
        seconds < 60 -> t("now", "ngayon")
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86400 -> "${seconds / 3600}h"
        else -> "${seconds / 86400}d"
    }

    // Home's Recent
    val seeAll = t("See all", "Lahat")
    val recentHidden = t("Recent saves hidden", "Nakatago ang mga na-save kanina")
    val tapToShow = t("Tap to show", "I-tap para ipakita")
    val hideRecent = t("Hide recent saves", "Itago ang mga na-save kanina")
    val showRecent = t("Show recent saves", "Ipakita ang mga na-save kanina")
    val recentOnHome = t("Recent saves on home", "Mga na-save sa home")
    val show = t("Show", "Ipakita")
    val hide = t("Hide", "Itago")
    val hideFromHome = t("Hide from home", "Itago sa home")
    val showOnHome = t("Show on home", "Ipakita sa home")

    // Library
    val library = t("Library", "Library")
    val all = t("All", "Lahat")
    val photos = t("Photos", "Photos")
    val videos = t("Videos", "Videos")
    val gifs = t("GIFs", "GIFs")
    val audio = t("Audio", "Audio")
    val favorites = t("Favorites", "Favorites")
    val trash = t("Trash", "Basurahan")
    val sortNew = t("Newest", "Pinakabago")
    val sortOld = t("Oldest", "Pinakaluma")
    val sortName = t("Name", "Pangalan")
    val sortSize = t("Size", "Laki")
    val sort = t("Sort", "Ayusin")
    val search = t("Search by name", "Hanapin ayon sa pangalan")
    val back = t("Back", "Bumalik")
    val newAlbum = t("New album", "Bagong album")
    val albumName = t("Album name", "Pangalan ng album")
    val rename = t("Rename", "Palitan ang pangalan")
    val deleteAlbum = t("Delete album", "Burahin ang album")
    fun deleteAlbumSub(name: String, n: Int) = t(
        "\"$name\" goes away. Its ${files(n)} move back to All, and nothing is deleted.",
        "Mawawala ang \"$name\". Babalik sa Lahat ang ${files(n)} nito, at walang mabubura.",
    )
    val noAlbum = t("No album", "Walang album")
    val moveTo = t("Move to album", "Ilipat sa album")
    fun moved(n: Int, album: String) = t("Moved ${files(n)} to $album", "Nailipat ang ${files(n)} sa $album")
    fun files(n: Int) = if (n == 1) "1 file" else "$n files"
    fun selectedN(n: Int) = t("$n selected", "$n ang napili")
    val share = t("Share", "I-share")
    val move = t("Move", "Ilipat")
    val favorite = t("Favorite", "Favorite")
    val unfavorite = t("Unfavorite", "Alisin sa favorites")
    val delete = t("Delete", "Burahin")
    val restore = t("Restore", "Ibalik")
    val deleteForever = t("Delete for good", "Burahin nang tuluyan")
    val emptyTrash = t("Empty trash", "Linisin ang basurahan")
    fun forGood(n: Int) = t("${files(n)} will be deleted for good.", "Tuluyang mabubura ang ${files(n)}.")
    // Android 11 and up empties MediaStore's trash on its own after 30 days. Android 10 has no trash of its own.
    val trashNote = if (android.os.Build.VERSION.SDK_INT >= 30) t("Android deletes files in the trash after 30 days.", "Binubura ng Android ang laman ng basurahan pagkalipas ng 30 araw.")
        else t("Files stay here until you empty the trash.", "Nandito lang sila hanggang linisin mo ang basurahan.")
    fun trashed(n: Int) = t("Moved ${files(n)} to the trash", "Nailipat ang ${files(n)} sa basurahan")
    fun restored(n: Int) = t("Restored ${files(n)}", "Naibalik ang ${files(n)}")
    fun deleted(n: Int) = t("Deleted ${files(n)} for good", "Tuluyang nabura ang ${files(n)}")
    fun notDone(n: Int) = t("${files(n)} didn't work", "Hindi gumana sa ${files(n)}")
    val undo = t("Undo", "I-undo")
    val cancel = t("Cancel", "Huwag na")
    val emptyAll = t("Nothing saved yet", "Wala pang na-save")
    val emptyFav = t("No favorites yet", "Wala pang favorites")
    val emptyFavSub = t("Tap ♡ on a photo or video to keep it here.", "I-tap ang ♡ sa photo o video para mapunta dito.")
    val emptyBin = t("The trash is empty", "Walang laman ang basurahan")
    val emptyAlbum = t("This album is empty", "Walang laman ang album na ito")
    val emptyAlbumSub = t("Long-press files in All, then move them here.", "I-long-press ang files sa Lahat, tapos ilipat dito.")
    fun emptySearch(q: String) = t("Nothing named \"$q\"", "Walang pangalang \"$q\"")
    val emptyKind = t("None of these yet", "Wala pa nito")

    // Viewer and editors
    fun kind(kind: String) = when (kind) {
        "photo" -> t("Photo", "Photo")
        "gif" -> "GIF"
        "audio" -> t("Audio", "Audio")
        else -> t("Video", "Video")
    }
    val openWith = t("Open in another app", "Buksan sa ibang app")
    val info = t("Info", "Info")
    val edit = t("Edit", "I-edit")
    val more = t("More", "Iba pa")
    val play = t("Play", "I-play")
    val pause = t("Pause", "I-pause")
    val infoName = t("Name", "Pangalan")
    val infoType = t("Type", "Uri")
    val infoSize = t("Dimensions", "Sukat")
    val infoLength = t("Length", "Haba")
    val infoFile = t("File size", "Laki ng file")
    val infoSaved = t("Saved", "Na-save")
    val infoAlbum = t("Album", "Album")
    val infoFolder = t("Folder", "Folder")
    val rotate = t("Rotate", "Iikot")
    val flip = t("Flip", "Baligtarin")
    val free = t("Free", "Malaya")
    val reset = t("Reset", "Ibalik")
    val saveCopy = t("Save copy", "I-save ang kopya")
    val savingCopy = t("Saving a copy…", "Sine-save ang kopya…")
    val savedCopy = t("Saved a copy", "Nag-save ng kopya")
    val trim = t("Trim", "Putulin")
    val trimStart = t("Start", "Simula")
    val trimEnd = t("End", "Dulo")
    val trimNote = t(
        "Cuts at the key frame just before the start, so it can begin a moment early. Nothing is re-encoded.",
        "Pumuputol sa key frame bago ang simula, kaya puwedeng mauna nang kaunti. Walang re-encode.",
    )
    val cantEdit = t("This one can't be edited on this phone", "Hindi ito ma-edit sa phone na ito")

    // Intro
    val introSkip = t("Skip", "Laktawan")
    val introNext = t("Next", "Susunod")
    val introStart = t("Start saving", "Simulan na")
    val introClose = t("Skip intro", "Laktawan ang intro")
    val showIntro = t("Show the intro again", "Ipakita ulit ang intro")
    val welcome = t("Hi, I'm Lodi!", "Hi, ako si Lodi!")
    val welcomeSub = t(
        "I save the photos and videos apps won't let you keep. Everything stays on your phone.",
        "Sine-save ko ang mga photo at video na ayaw ipa-save ng apps. Sa phone mo lang lahat.",
    )
    val ways = t("Three ways to save", "Tatlong paraan para mag-save")
    val wayShare = t("Share to LodiSave", "I-share sa LodiSave")
    val wayShareSub = t("In any app, tap Share, then LodiSave.", "Sa kahit anong app, i-tap ang Share, tapos LodiSave.")
    val wayCopy = t("Copy a link", "Mag-copy ng link")
    val wayCopySub = t(
        "Copy a post's link, then press Save here or tap the home screen widget.",
        "I-copy ang link ng post, tapos pindutin ang I-save dito o i-tap ang widget sa home screen.",
    )
    val wayLodi = t("Tap the floating Lodi", "I-tap ang lumulutang na Lodi")
    val wayLodiSub = t("Lodi floats over every app. Copy a link, then tap him.", "Lumulutang si Lodi sa ibabaw ng bawat app. Mag-copy ng link, tapos i-tap siya.")
    val pickIntro = t(
        "Tick round circles on photos and videos in Threads, Instagram, Reddit or any app. It takes two switches.",
        "Mag-tick ng mga bilog sa photo at video sa Threads, Instagram, Reddit o kahit anong app. Dalawang switch lang.",
    )
    val stepBubbleSub = t("Android asks you to let LodiSave show over other apps.", "Hihingi ang Android na payagang lumabas ang LodiSave sa ibabaw ng ibang apps.")
    val stepPicker = t("LodiSave picker", "LodiSave picker")
    val stepPickerSub = t("In Accessibility, open LodiSave picker and turn it on.", "Sa Accessibility, buksan ang LodiSave picker at i-on.")
    val turnOn = t("Turn on", "I-on")
    val notifyTitle = t("Know when it's saved", "Malaman kung na-save na")
    val notifySub = t(
        "Your notifications show how a save is going, and it keeps running after you leave the app.",
        "Makikita mo sa notifications kung kumusta na ang save, at tuloy ito kahit umalis ka sa app.",
    )
    val notifications = t("Notifications", "Notifications")
    val allow = t("Allow", "Payagan")
    val allSet = t("You're all set!", "Ayos na tayo!")
    val allSetSub = t("Copy a link or share a post to LodiSave to save it.", "Mag-copy ng link o i-share ang post sa LodiSave para i-save.")
    val accountsOptional = t(
        "Optional: sign in to Instagram, Threads, Facebook, X, TikTok or Reddit in Settings › Accounts to save stories and posts that need a login.",
        "Optional: mag-sign in sa Instagram, Threads, Facebook, X, TikTok o Reddit sa Settings › Accounts para ma-save ang stories at posts na kailangan ng login.",
    )

    // Gaming mode
    val gaming = t("Gaming mode", "Gaming mode")
    val auto = "Auto"
    val gamingHint = t(
        "When a game is in front, Lodi hides, picking stops, saves stay quiet and LodiSave waits to update its reader.",
        "Kapag may laro sa harap, nagtatago si Lodi, tigil muna ang pagpili, tahimik ang saves at hinihintay muna bago i-update ang reader.",
    )
    val gamingNeedsPicker = t(
        "Gaming mode needs Pick in other apps turned on: only the picker can see which app is in front.",
        "Kailangan naka-on ang Pumili sa ibang apps para gumana ang gaming mode: ang picker lang ang nakakakita kung anong app ang nasa harap.",
    )
    val quiet = t("Saving while you play", "Nagse-save habang naglalaro ka")

    // Accounts
    val accounts = t("Accounts", "Accounts")
    val accountsHint = t("Sign in to save stories and posts that need a login.", "Mag-sign in para ma-save ang stories at posts na kailangan ng login.")
    fun signedInN(n: Int) = t("$n signed in", "$n ang naka-sign in")
    val signIn = t("Sign in", "Mag-sign in")
    val signOut = t("Sign out", "Mag-sign out")
    val signedIn = t("Signed in", "Naka-sign in")
    val notSignedIn = t("Not signed in", "Hindi naka-sign in")
    fun signInTo(site: String) = t("Sign in to $site", "Mag-sign in sa $site")
    fun signedInTo(site: String) = t("Signed in to $site!", "Naka-sign in ka na sa $site!")
    val safeTitle = t("Why this is safe", "Bakit ito ligtas")
    /** The safety note. Each line holds for what Accounts does; `site` is null for all sites at once. */
    fun safe(site: String?): String {
        val n = site ?: t("the site", "site")
        return t(
            "You type your password on $n's own page, so LodiSave never sees or keeps it. " +
                "LodiSave keeps only the sign-in cookies $n gives this phone, in LodiSave's private storage, never in backups. " +
                "They're sent only to $n, only when you save. LodiSave has no servers, so nothing goes to LodiSave or anyone else. " +
                "Sign out here anytime to delete them.",
            "Sa sariling page ng $n mo ita-type ang password mo, kaya hindi ito nakikita o tinatago ng LodiSave. " +
                "Ang sign-in cookies lang na ibinibigay ng $n sa phone na ito ang tinatago ng LodiSave, sa private storage ng LodiSave, hindi kailanman sa backups. " +
                "Sa $n lang ito ipinapadala, at kapag nagse-save ka lang. Walang servers ang LodiSave, kaya walang napupunta sa LodiSave o kahit kanino. " +
                "Mag-sign out dito kahit kailan para burahin ito.",
        )
    }
    fun caveat(site: String?): String {
        val n = site ?: t("the site", "site")
        return t(
            "Use it for posts your account can already see. Saving lots of posts quickly can make $n ask you to confirm it's you.",
            "Gamitin ito sa posts na nakikita na ng account mo. Kapag maraming posts ang sine-save nang mabilisan, puwedeng ipa-confirm sa iyo ng $n na ikaw nga iyan.",
        )
    }
    val signInTip = t(
        "Use your username or email and password. Google and Apple buttons open your browser, which can't sign LodiSave in.",
        "Gamitin ang username o email at password mo. Bubuksan ng Google at Apple buttons ang browser mo, na hindi makaka-sign in sa LodiSave.",
    )
    val gotIt = t("Got it", "Sige")
    fun openedOutside(site: String) = t("Opened in your browser: that page isn't part of $site.", "Binuksan sa browser mo: hindi bahagi ng $site ang page na iyon.")

    companion object {
        fun of(ctx: Context) = Copy(Prefs(ctx).lang == "tl")
    }
}

/** In-app settings. Motion is LodiSave's own switch, so a phone set to reduce motion still gets Lodi's animations unless this is off. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("lodisave", Context.MODE_PRIVATE)
    var lang: String
        get() = sp.getString("lang", "en") ?: "en"
        set(v) = sp.edit { putString("lang", v) }
    var motion: Boolean
        get() = sp.getBoolean("motion", true)
        set(v) = sp.edit { putBoolean("motion", v) }
    var askedNotifications: Boolean
        get() = sp.getBoolean("askedNotifications", false)
        set(v) = sp.edit { putBoolean("askedNotifications", v) }
    var bubble: Boolean
        get() = sp.getBoolean("bubble", false)
        set(v) = sp.edit { putBoolean("bubble", v) }
    var bubbleY: Int
        get() = sp.getInt("bubbleY", 600)
        set(v) = sp.edit { putInt("bubbleY", v) }
    var bubbleLeft: Boolean
        get() = sp.getBoolean("bubbleLeft", false)
        set(v) = sp.edit { putBoolean("bubbleLeft", v) }
    /** Home shows no thumbnails of recent saves. */
    var hideRecent: Boolean
        get() = sp.getBoolean("hideRecent", false)
        set(v) = sp.edit { putBoolean("hideRecent", v) }
    /** MediaStore ids of saves kept off home's Recent. */
    var hiddenFromHome: Set<String>
        get() = sp.getStringSet("hiddenFromHome", null).orEmpty().toSet()
        set(v) = sp.edit { putStringSet("hiddenFromHome", v) }
    /** Favorites MediaStore wouldn't keep, by id: all of them on Android 10, which has no IS_FAVORITE. */
    var favorites: Set<String>
        get() = sp.getStringSet("favorites", null).orEmpty().toSet()
        set(v) = sp.edit { putStringSet("favorites", v) }
    /** The trash on Android 10, which has no MediaStore trash, by id. */
    var trashed: Set<String>
        get() = sp.getStringSet("trashed", null).orEmpty().toSet()
        set(v) = sp.edit { putStringSet("trashed", v) }
    /** Albums made in the Library that have no files yet. A folder only exists while a file is in it. */
    var albums: Set<String>
        get() = sp.getStringSet("albums", null).orEmpty().toSet()
        set(v) = sp.edit { putStringSet("albums", v) }
    /** The intro was seen. */
    var intro: Boolean
        get() = sp.getBoolean("intro", false)
        set(v) = sp.edit { putBoolean("intro", v) }
    /** Gaming mode: "auto" turns it on while a game is in front, "off" never. */
    var gaming: String
        get() = sp.getString("gaming", "auto") ?: "auto"
        set(v) = sp.edit { putString("gaming", v) }
}
