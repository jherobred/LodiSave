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
    val failed = t("That didn't work", "Hindi gumana")

    /** Title and explanation for an error code from the save engine, or its own message. */
    fun error(code: String): Pair<String, String> = when (code) {
        "login" -> t("This one needs a login", "Kailangan mag-login dito") to
            t("LodiSave can save public posts only, for now.", "Pampublikong post lang ang kaya ng LodiSave sa ngayon.")
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
            t("Copy a link first, then tap the widget.", "Mag-copy muna ng link, tapos i-tap ang widget.")
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
}
