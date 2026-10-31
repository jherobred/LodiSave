package io.github.jherobred.lodisave

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import android.webkit.WebSettings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/** A story or post on a profile: a photo or video file and its preview, both on the site's servers. */
private data class Found(val kind: String, val src: String, val thumb: String, val name: String)

private fun JSONArray?.found() = this?.let { a -> List(a.length()) { a.getJSONObject(it) } }.orEmpty()
    .map { Found(it.optString("kind"), it.optString("src"), it.optString("thumb"), it.optString("name")) }.filter { it.src.isNotEmpty() }

/**
 * A profile's stories, highlights and posts by username, to pick from and save: Instagram's (with
 * a sign-in) and Threads'. It shows what the account signed in to LodiSave can see, so a private
 * account's only when that account follows it. Reading never marks a story seen.
 */
@Composable
fun Profile(copy: Copy, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    var site by rememberSaveable { mutableStateOf("instagram") }
    var user by rememberSaveable { mutableStateOf("") }
    var answer by remember { mutableStateOf<JSONObject?>(null) }
    var posts by remember { mutableStateOf(emptyList<Found>()) }
    var highlight by remember { mutableStateOf<Pair<String, List<Found>>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    val ua = remember { runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrNull() }
    val home = if (site == "threads") "https://www.threads.com/@$user" else "https://www.instagram.com/$user/"

    fun read(part: String, cursor: String? = null, then: (JSONObject) -> Unit) {
        loading = true
        scope.launch {
            val r = profileOf(ctx, site, user, part, cursor, ua)
            loading = false
            if (r.has("error")) answer = r else then(r)
        }
    }
    fun look() {
        user = user.trim().removePrefix("@")
        if (user.isEmpty()) return
        picked = emptySet()
        highlight = null
        read("all") { answer = it; posts = it.optJSONArray("posts").found() }
    }

    Column(Modifier.fillMaxSize().background(t.ground).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundButton(Glyph.Back, copy.back, onBack)
            Text(copy.profiles, Modifier.weight(1f), fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = t.ink)
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((key, label) in listOf("instagram" to "Instagram", "threads" to "Threads", "facebook" to "Facebook")) {
                Chip(label, site == key, { site = key; answer = null; posts = emptyList(); picked = emptySet() })
            }
        }
        if (site == "facebook") Text(copy.facebookProfiles, Modifier.padding(16.dp), fontFamily = Geist, fontSize = 14.sp, color = t.muted)
        else Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                user, { user = it },
                Modifier.weight(1f).heightIn(min = 48.dp).clip(CircleShape).background(t.surface).border(1.dp, t.line, CircleShape).padding(horizontal = 18.dp, vertical = 14.dp),
                singleLine = true,
                textStyle = TextStyle(fontFamily = GeistMono, fontSize = 14.sp, color = t.ink),
                cursorBrush = SolidColor(t.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { look() }),
                decorationBox = { field -> if (user.isEmpty()) Text("@${copy.username.lowercase()}", color = t.muted, fontFamily = GeistMono, fontSize = 14.sp); field() },
            )
            Pill(copy.look, ::look, enabled = user.isNotBlank() && !loading)
        }
        if (loading) Meter(-1f, Modifier.padding(horizontal = 16.dp))
        val r = answer
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                r == null -> Text(copy.unseen, Modifier.padding(16.dp), fontFamily = Geist, fontSize = 13.sp, color = t.muted)
                r.has("error") -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val (title, sub) = copy.error(r.getString("error"), Site.of(home)?.label, Site.of(home)?.signedIn() == true)
                    Text(title, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = t.ink)
                    Text(sub, fontFamily = Geist, fontSize = 14.sp, color = t.muted)
                    if (r.getString("error") == "login") SignInPill(copy, home) { look() }
                }
                else -> LazyVerticalGrid(
                    GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(r.optString("name"), fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = t.ink)
                            Text("@${r.optString("user")}", fontFamily = GeistMono, fontSize = 12.sp, color = t.muted)
                            if (r.optBoolean("private") && !r.optBoolean("followed")) Text(copy.privateAccount, fontFamily = Geist, fontSize = 13.sp, color = t.muted)
                            if (site == "threads") Text(copy.threadsStories, fontFamily = Geist, fontSize = 13.sp, color = t.muted)
                        }
                    }
                    if (site == "instagram") {
                        val stories = r.optJSONArray("stories").found()
                        item(span = { GridItemSpan(maxLineSpan) }) { Heading(if (stories.isEmpty()) "${copy.stories} · ${copy.noStories}" else copy.stories) }
                        items(stories, key = { "s" + it.src }) { Tile(it, it.src in picked) { picked = picked.toggled(it.src) } }
                        val tray = r.optJSONArray("highlights")?.let { a -> List(a.length()) { a.getJSONObject(it) } }.orEmpty()
                        if (tray.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) { Heading(copy.highlights) }
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(tray, key = { it.optString("id") }) { h ->
                                        Chip(h.optString("title").ifEmpty { "★" }, highlight?.first == h.optString("id"), {
                                            read(h.optString("id")) { a -> highlight = h.optString("id") to a.optJSONArray("items").found() }
                                        })
                                    }
                                }
                            }
                            highlight?.let { (id, found) -> items(found, key = { "h$id" + it.src }) { Tile(it, it.src in picked) { picked = picked.toggled(it.src) } } }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { Heading(if (posts.isEmpty()) "${copy.posts} · ${copy.noPosts}" else copy.posts) }
                    items(posts, key = { "p" + it.src }) { Tile(it, it.src in picked) { picked = picked.toggled(it.src) } }
                    r.optString("next").takeIf { it.isNotEmpty() && it != "null" }?.let { next ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(copy.loadMore, Modifier.padding(vertical = 12.dp).clip(CircleShape).clickable {
                                read("posts", next) { a -> posts = posts + a.optJSONArray("posts").found(); answer = JSONObject(r.toString()).put("next", a.opt("next")) }
                            }.padding(12.dp), color = t.accent, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }
                }
            }
            // Saves the picked ones as their own files, each in the background like a shared link.
            if (picked.isNotEmpty()) Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                Pill(copy.saveN(picked.size), {
                    val all = (r?.optJSONArray("stories").found() + posts + highlight?.second.orEmpty()).distinctBy { it.src }
                    for (f in all.filter { it.src in picked }) SaveWorker.enqueue(ctx, f.src, file = true, page = home, name = f.name)
                    android.widget.Toast.makeText(ctx, copy.savingN(picked.size), android.widget.Toast.LENGTH_SHORT).show()
                    picked = emptySet()
                })
            }
        }
    }
}

private fun Set<String>.toggled(v: String) = if (v in this) this - v else this + v

@Composable
private fun Heading(text: String) = Text(text, Modifier.padding(top = 10.dp, bottom = 2.dp), fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = LocalTokens.current.muted)

@Composable
private fun Tile(found: Found, on: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    val picture = rememberWebThumb(found.thumb)
    val shape = RoundedCornerShape(if (on) 20.dp else 14.dp)
    Box(Modifier.aspectRatio(1f).clip(shape).background(t.soft).then(if (on) Modifier.border(3.dp, t.pick, shape) else Modifier).clickable(onClick = onClick)) {
        picture?.let { Image(it, found.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (found.kind == "video") GlyphIcon(Glyph.Play, Modifier.align(Alignment.BottomStart).padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(4.dp), Color.White, 18.dp)
        Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(24.dp).clip(CircleShape).background(if (on) t.pick else Color.Black.copy(alpha = 0.3f)).border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
            if (on) GlyphIcon(Glyph.Check, tint = Color.White, size = 16.dp)
        }
    }
}

/** The engine's look at a profile (lodi.profile), signed in to its site when the user is. */
private suspend fun profileOf(ctx: Context, site: String, user: String, part: String, cursor: String?, ua: String?): JSONObject = withContext(Dispatchers.IO) {
    synchronized(SaveWorker) { if (!Python.isStarted()) Python.start(AndroidPlatform(ctx)) }
    val cookies = Site.cookieFile(ctx, if (site == "threads") "https://www.threads.com/" else "https://www.instagram.com/", "profile")
    try {
        runCatching { JSONObject(Python.getInstance().getModule("lodi").callAttr("profile", site, user, part, cursor, cookies?.path, ua).toString()) }
            .getOrElse { JSONObject().put("error", it.message ?: "nothing") }
    } finally {
        cookies?.delete()
    }
}

/** Previews from a site's servers, kept while LodiSave runs. */
private object WebThumbs {
    val cache = object : LruCache<String, ImageBitmap>(24 shl 20) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    suspend fun load(url: String): ImageBitmap? = cache.get(url) ?: withContext(Dispatchers.IO) {
        runCatching {
            val bytes = URL(url).openConnection().apply { connectTimeout = 15_000; readTimeout = 30_000 }.getInputStream().use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
            val step = generateSequence(1) { it * 2 }.first { maxOf(bounds.outWidth, bounds.outHeight) / it <= 400 }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = step })?.asImageBitmap()
        }.getOrNull()?.also { cache.put(url, it) }
    }
}

@Composable
private fun rememberWebThumb(url: String): ImageBitmap? =
    produceState(if (url.isEmpty()) null else WebThumbs.cache.get(url), url) { if (url.isNotEmpty()) value = WebThumbs.load(url) }.value
