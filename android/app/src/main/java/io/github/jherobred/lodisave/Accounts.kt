package io.github.jherobred.lodisave

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A site you can sign in to, so saves can open stories and posts your account can see. You sign
 * in on the site's own login page in a WebView ([SignInActivity]). The site's cookies stay in that
 * WebView's own store in LodiSave's private storage, which backups leave out, and go to the save
 * engine only for a link of that site. `hosts` are the site's own domains, `links` its short links,
 * and `session` the cookies that only a signed-in visitor has (any one set of them).
 */
enum class Site(val label: String, val login: String, val hosts: List<String>, private val links: List<String>, private val session: List<List<String>>) {
    Instagram("Instagram", "https://www.instagram.com/accounts/login/", listOf("instagram.com"), listOf("instagr.am"), listOf(listOf("sessionid"))),
    // Threads signs in on its own pages, not Instagram's.
    Threads("Threads", "https://www.threads.com/login", listOf("threads.com", "threads.net"), emptyList(), listOf(listOf("sessionid"))),
    Facebook("Facebook", "https://www.facebook.com/login/", listOf("facebook.com"), listOf("fb.watch", "fb.com", "fb.me"), listOf(listOf("c_user", "xs"))),
    X("X", "https://x.com/i/flow/login", listOf("x.com", "twitter.com"), emptyList(), listOf(listOf("auth_token"))),
    TikTok("TikTok", "https://www.tiktok.com/login", listOf("tiktok.com"), emptyList(), listOf(listOf("sessionid"))),
    Reddit("Reddit", "https://www.reddit.com/login/", listOf("reddit.com"), listOf("redd.it"), listOf(listOf("reddit_session"), listOf("token_v2")));

    /** Whether a web address belongs to this site. */
    fun owns(host: String?) = host != null && hosts.any { host == it || host.endsWith(".$it") }

    /** The site's cookies the WebView keeps for one of its domains, by name. Main thread. */
    private fun jar(host: String): Map<String, String> {
        val cookies = CookieManager.getInstance()
        return listOf("https://$host/", "https://www.$host/").flatMap { url ->
            cookies.getCookie(url).orEmpty().split(';').mapNotNull { c ->
                c.trim().split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotEmpty() }?.let { it[0] to it[1] }
            }
        }.toMap()
    }

    /** Signed in: the WebView holds the cookies only a signed-in visitor gets. Main thread. */
    fun signedIn(): Boolean {
        val all = hosts.flatMap { jar(it).entries }.associate { it.key to it.value }
        return session.any { names -> names.all { !all[it].isNullOrEmpty() } }
    }

    /** Deletes the site's cookies and what its pages stored, then writes the store to disk. Main thread. */
    fun signOut() {
        val cookies = CookieManager.getInstance()
        for (host in hosts) for (sub in listOf("", "www.", "m.", "mobile.", "web.")) {
            val url = "https://$sub$host/"
            for (name in cookies.getCookie(url).orEmpty().split(';').map { it.trim().substringBefore('=') }.filter { it.isNotEmpty() }) {
                // Expired both ways, whether the site set it for all of itself or for this address alone.
                cookies.setCookie(url, "$name=; Max-Age=0; Path=/; Secure")
                cookies.setCookie(url, "$name=; Max-Age=0; Path=/; Secure; Domain=$host")
            }
            WebStorage.getInstance().deleteOrigin(url.trimEnd('/'))
        }
        cookies.flush()
    }

    companion object {
        /** The site a link belongs to, its short links like fb.watch and redd.it included, or null. */
        fun of(url: String): Site? {
            val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return null
            return entries.firstOrNull { s -> s.owns(host) || s.links.any { host == it || host.endsWith(".$it") } }
        }

        /**
         * The sign-in for a save of `url`, as a Netscape cookies.txt that the engine and yt-dlp read,
         * or null when you aren't signed in to its site. It holds that site's cookies only, each for
         * its own domain, so they go to that site alone. In noBackupFilesDir, readable by LodiSave
         * only; the save deletes it when it ends.
         */
        suspend fun cookieFile(ctx: Context, url: String, name: String): File? {
            val site = of(url) ?: return null
            val until = System.currentTimeMillis() / 1000 + 86400
            val lines = withContext(Dispatchers.Main) {
                if (!site.signedIn()) null
                else site.hosts.flatMap { host -> site.jar(host).map { (key, value) -> ".$host\tTRUE\t/\tTRUE\t$until\t$key\t$value" } }
            } ?: return null
            val file = File(ctx.noBackupFilesDir, "cookies-$name.txt")
            file.delete()
            file.createNewFile()
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
            file.writeText("# Netscape HTTP Cookie File\n" + lines.joinToString("\n") + "\n")
            return file
        }
    }
}

/**
 * Signing in to a site on its own login page. The WebView shows the site's page and nothing of
 * LodiSave's: no script added or run, no bridge to the app, nothing read from what you type. Pages
 * off the site open in the browser instead. Done when the site's sign-in cookies show up.
 */
class SignInActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val site = intent.getStringExtra(EXTRA_SITE)?.let { name -> Site.entries.firstOrNull { it.name == name } } ?: return finish()
        // A sign-in the site stopped taking, as after a save that needed a login: start over clean.
        if (savedInstanceState == null && site.signedIn()) site.signOut()
        setContent { LodiTheme { SignIn(site, onDone = { setResult(RESULT_OK); finish() }, onClose = ::finish) } }
    }

    companion object {
        private const val EXTRA_SITE = "site"
        fun intent(ctx: Context, site: Site): Intent = Intent(ctx, SignInActivity::class.java).putExtra(EXTRA_SITE, site.name)
    }
}

@Composable
private fun SignIn(site: Site, onDone: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val copy = remember { Copy.of(ctx) }
    var note by rememberSaveable { mutableStateOf(true) }
    var done by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var web by remember { mutableStateOf<WebView?>(null) }
    // Some sites sign in without loading a new page, so the store is checked while the screen is up.
    LaunchedEffect(Unit) {
        while (!site.signedIn()) delay(500)
        web?.stopLoading()
        // Only the cookies stay: the pages' cache and what they stored go.
        web?.clearCache(true)
        for (host in site.hosts) for (origin in listOf("https://$host", "https://www.$host")) WebStorage.getInstance().deleteOrigin(origin)
        CookieManager.getInstance().flush()
        done = true
        delay(1200)
        onDone()
    }
    Column(Modifier.fillMaxSize().background(t.ground).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundButton(Glyph.Close, copy.cancel, onClose)
            Column(Modifier.weight(1f)) {
                Text(copy.signInTo(site.label), fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = t.ink)
                // Where the page comes from, as a browser's address bar would say.
                Text(site.login.removePrefix("https://").substringBefore('/'), fontFamily = GeistMono, fontSize = 12.sp, color = t.muted)
            }
        }
        if (loading && !done) Meter(-1f, Modifier.padding(horizontal = 16.dp))
        AnimatedContent(done, Modifier.weight(1f).fillMaxWidth(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "signin") { signed ->
            if (signed) Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Lodi(Pose.Yay, Modifier.size(140.dp), LocalMotion.current)
                Text(copy.signedInTo(site.label), fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = t.ink)
            } else AndroidView(
                { c -> signInView(c, site, copy) { loading = it }.also { web = it } },
                Modifier.fillMaxSize(),
                onRelease = { it.destroy(); web = null },
            )
        }
        Column(Modifier.padding(12.dp)) {
            SafetyNote(copy, site.label, note, { note = !note })
        }
    }
}

/** The WebView for a site's login page, set up so only the site's own page runs in it. */
@SuppressLint("SetJavaScriptEnabled") // the login page needs its own scripts; LodiSave adds none
private fun signInView(ctx: Context, site: Site, copy: Copy, onLoading: (Boolean) -> Unit) = WebView(ctx).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.setSupportMultipleWindows(false)
    settings.javaScriptCanOpenWindowsAutomatically = false
    CookieManager.getInstance().setAcceptCookie(true)
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // A frame inside the page, like a captcha, is the site's business.
            if (!request.isForMainFrame) return false
            val url = request.url
            if (url.scheme == "https" && site.owns(url.host)) return false
            // Off the site: the browser opens it, not this sign-in screen. Not a web page at all: nothing.
            if (url.scheme == "https" || url.scheme == "http") {
                runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                Toast.makeText(view.context, copy.openedOutside(site.label), Toast.LENGTH_SHORT).show()
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) = onLoading(true)
        override fun onPageFinished(view: WebView, url: String?) = onLoading(false)
    }
    loadUrl(site.login)
}

/** Why signing in is safe, and the caveat. Opens and closes with a tap when `onToggle` is given. */
@Composable
fun SafetyNote(copy: Copy, site: String?, open: Boolean, onToggle: (() -> Unit)? = null) {
    val t = LocalTokens.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(t.soft)
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier).padding(14.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlyphIcon(Glyph.Lock, tint = t.ok, size = 18.dp)
            Text(copy.safeTitle, Modifier.weight(1f), fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = t.ink)
            if (onToggle != null) Text(if (open) copy.gotIt else "›", color = t.accent, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        if (open) {
            Text(copy.safe(site), fontFamily = Geist, fontSize = 13.sp, color = t.ink)
            Text(copy.caveat(site), fontFamily = Geist, fontSize = 13.sp, color = t.muted)
            if (site != null) Text(copy.signInTip, fontFamily = Geist, fontSize = 13.sp, color = t.muted)
        }
    }
}

/** Settings › Accounts: each site, whether you're signed in, Sign in or Sign out, and why it's safe. */
@Composable
fun AccountsSheet(copy: Copy, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    var version by remember { mutableIntStateOf(0) }
    val signedIn = remember(version) { Site.entries.filter { it.signedIn() }.toSet() }
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { version++ }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = t.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(copy.accounts, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = t.ink)
            Text(copy.accountsHint, fontFamily = Geist, fontSize = 13.sp, color = t.muted)
            for (site in Site.entries) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(site.label, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = t.ink)
                    Text(if (site in signedIn) copy.signedIn else copy.notSignedIn, fontFamily = Geist, fontSize = 13.sp, color = if (site in signedIn) t.ok else t.muted)
                }
                if (site in signedIn) Pill(copy.signOut, { site.signOut(); version++ }, primary = false)
                else Pill(copy.signIn, { signIn.launch(SignInActivity.intent(ctx, site)) })
            }
            SafetyNote(copy, null, open = true)
        }
    }
}

/** A "Sign in to <site>" button for a save that needed a login, which saves again once you're in. */
@Composable
fun SignInPill(copy: Copy, link: String, onSignedIn: () -> Unit) {
    val ctx = LocalContext.current
    val site = Site.of(link) ?: return
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == android.app.Activity.RESULT_OK) onSignedIn() }
    Pill(copy.signInTo(site.label), { signIn.launch(SignInActivity.intent(ctx, site)) })
}
