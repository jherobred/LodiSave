package io.github.jherobred.lodisave

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The first web link in the clipboard, copied at `since` (System.currentTimeMillis) or later.
 *  Android only shares the clipboard with the app in focus. */
fun copiedLink(ctx: Context, since: Long = 0): String? {
    val clip = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    if (clip.itemCount == 0 || clip.description.timestamp in 1 until since) return null
    val text = clip.getItemAt(0).coerceToText(ctx)?.toString() ?: return null
    return LINK.find(text)?.value
}

// Not the punctuation that ends a sentence around a link in a chat: "look https://x.com/a/status/1)."
val LINK = Regex("""https?://[^\s"'<>]*[^\s"'<>.,;:!?)\]}]""")

fun short(url: String) = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")

fun openSaved(ctx: Context, uri: Uri, mime: String, choose: Boolean = false) {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime.ifEmpty { "*/*" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(if (choose) Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else view) }
}

/** Hands saved files to another app's share, leaving LodiSave itself out of the list. */
fun share(ctx: Context, items: List<Saved>) {
    if (items.isEmpty()) return
    val uris = ArrayList(items.map { it.uri })
    val type = items.map { it.mime }.distinct().singleOrNull()
        ?: items.map { it.mime.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
    val send = (if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris))
        .setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    val chooser = Intent.createChooser(send, null)
        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(ctx, ShareActivity::class.java)))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(chooser) }
}

fun sizeText(b: Long) = if (b >= 1 shl 20) "%.1f MB".format(b / 1048576.0) else "${(b / 1024).coerceAtLeast(1)} KB"

/** A length in milliseconds as a clock: 0:07, 3:12 or 1:02:45. */
fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Thumbnails kept while LodiSave runs, so a grid shows them at once when you come back to it and
 *  the viewer opens on one. */
object Thumbs {
    private val cache = object : LruCache<Long, ImageBitmap>(64 shl 20) {
        override fun sizeOf(key: Long, value: ImageBitmap) = value.width * value.height * 4
    }

    fun get(id: Long): ImageBitmap? = cache.get(id)

    suspend fun load(ctx: Context, item: Saved): ImageBitmap? = cache.get(item.id) ?: withContext(Dispatchers.IO) {
        runCatching { ctx.contentResolver.loadThumbnail(item.uri, android.util.Size(320, 320), null).asImageBitmap() }.getOrNull()
            ?.also { cache.put(item.id, it) }
    }
}

@Composable
fun rememberThumb(item: Saved): ImageBitmap? {
    val ctx = LocalContext.current
    return produceState(Thumbs.get(item.id), item.id) { value = Thumbs.load(ctx, item) }.value
}

/** The shared transition around the screens, and the screen a composable is on, for [Hero]. */
val LocalShared = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalScreen = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** A thumbnail that grows into the viewer and back: a shared element between the two screens,
 *  while motion is on. `modifier` sizes it. */
@Composable
fun Hero(key: Any, modifier: Modifier = Modifier, shape: Shape = RectangleShape, content: @Composable BoxScope.() -> Unit) {
    val shared = LocalShared.current
    val screen = LocalScreen.current
    val hero = if (shared == null || screen == null || !LocalMotion.current) Modifier else with(shared) {
        Modifier.sharedElement(
            rememberSharedContentState(key), screen,
            boundsTransform = BoundsTransform { _, _ -> spring(dampingRatio = 0.9f, stiffness = 420f) },
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
    Box(hero.then(modifier).clip(shape), content = content)
}

/** Fades and lifts its content in, `order` beats after it first shows, for staggered entrances. A
 *  negative order, or motion off, shows it at once. */
@Composable
fun Enter(order: Int, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val shown = remember { Animatable(if (order >= 0) 0f else 1f) }
    val motion = LocalMotion.current
    LaunchedEffect(Unit) {
        if (shown.value >= 1f) return@LaunchedEffect
        if (!motion) return@LaunchedEffect shown.snapTo(1f)
        delay(order.coerceAtMost(12) * 45L)
        shown.animateTo(1f, drift())
    }
    Box(modifier.graphicsLayer { alpha = shown.value.coerceIn(0f, 1f); translationY = (1f - shown.value) * 28.dp.toPx() }, content = content)
}

/** How much something pressed shrinks: a quick spring in and back out, as [Pill] does. */
@Composable
fun pressScale(press: InteractionSource, by: Float = 0.92f): Float {
    val pressed by press.collectIsPressedAsState()
    return animateFloatAsState(if (pressed && LocalMotion.current) by else 1f, snap(), label = "press").value
}

/** Line icons in a 24-unit square, drawn like [SlidersIcon]. */
enum class Glyph(d: String, val fill: Boolean = false) {
    Back("M19 12H5M11 6L5 12L11 18"),
    Close("M6 6L18 18M18 6L6 18"),
    Eye("M2.5 12C4.8 7.5 8.1 5.5 12 5.5C15.9 5.5 19.2 7.5 21.5 12C19.2 16.5 15.9 18.5 12 18.5C8.1 18.5 4.8 16.5 2.5 12ZM15 12A3 3 0 1 1 9 12A3 3 0 1 1 15 12Z"),
    EyeOff("M2.5 12C4.8 7.5 8.1 5.5 12 5.5C15.9 5.5 19.2 7.5 21.5 12C19.2 16.5 15.9 18.5 12 18.5C8.1 18.5 4.8 16.5 2.5 12ZM15 12A3 3 0 1 1 9 12A3 3 0 1 1 15 12ZM4 4L20 20"),
    Search("M16.5 10.5A6 6 0 1 1 4.5 10.5A6 6 0 1 1 16.5 10.5ZM15 15L20 20"),
    Sort("M4 7H20M4 12H15M4 17H10"),
    Share("M20.5 5.5A2.5 2.5 0 1 1 15.5 5.5A2.5 2.5 0 1 1 20.5 5.5ZM8.5 12A2.5 2.5 0 1 1 3.5 12A2.5 2.5 0 1 1 8.5 12ZM20.5 18.5A2.5 2.5 0 1 1 15.5 18.5A2.5 2.5 0 1 1 20.5 18.5ZM8.2 10.8L15.8 6.7M8.2 13.2L15.8 17.3"),
    Heart("M12 20C12 20 3 14.6 3 8.7C3 5.9 5.1 3.8 7.7 3.8C9.5 3.8 11.1 4.8 12 6.3C12.9 4.8 14.5 3.8 16.3 3.8C18.9 3.8 21 5.9 21 8.7C21 14.6 12 20 12 20Z"),
    HeartFull("M12 20C12 20 3 14.6 3 8.7C3 5.9 5.1 3.8 7.7 3.8C9.5 3.8 11.1 4.8 12 6.3C12.9 4.8 14.5 3.8 16.3 3.8C18.9 3.8 21 5.9 21 8.7C21 14.6 12 20 12 20Z", fill = true),
    Trash("M4 7H20M9.5 7V4.5H14.5V7M6.5 7L7.4 19.2C7.5 19.7 7.9 20 8.4 20H15.6C16.1 20 16.5 19.7 16.6 19.2L17.5 7M10 11V16M14 11V16"),
    Edit("M4 20H8L18.5 9.5C19.3 8.7 19.3 7.5 18.5 6.7L17.3 5.5C16.5 4.7 15.3 4.7 14.5 5.5L4 16V20ZM13 7L17 11"),
    More("M12 4.5A1.6 1.6 0 1 1 12 7.7A1.6 1.6 0 1 1 12 4.5ZM12 10.4A1.6 1.6 0 1 1 12 13.6A1.6 1.6 0 1 1 12 10.4ZM12 16.3A1.6 1.6 0 1 1 12 19.5A1.6 1.6 0 1 1 12 16.3Z", fill = true),
    Info("M21 12A9 9 0 1 1 3 12A9 9 0 1 1 21 12ZM12 11V16.5M12 7.6V7.5"),
    Folder("M3 7.5V17.5C3 18.6 3.9 19.5 5 19.5H19C20.1 19.5 21 18.6 21 17.5V9.5C21 8.4 20.1 7.5 19 7.5H12L10 5H5C3.9 5 3 5.9 3 7V7.5Z"),
    Plus("M12 5V19M5 12H19"),
    Check("M5 12.5L10 17.5L19 7"),
    Play("M8 5.5V18.5L19 12Z", fill = true),
    Pause("M7 5H10.5V19H7ZM13.5 5H17V19H13.5Z", fill = true),
    Rotate("M19.5 12A7.5 7.5 0 1 1 15.75 5.5M14.65 2.5L15.75 5.5L12.6 6.06"),
    Restore("M4.5 12A7.5 7.5 0 1 0 8.25 5.5M9.35 2.5L8.25 5.5L11.4 6.06"),
    Flip("M12 3V21M9 7L3.5 17H9ZM15 7L20.5 17H15Z"),
    Open("M14 4H20V10M20 4L11 13M18 14V18.5C18 19.3 17.3 20 16.5 20H5.5C4.7 20 4 19.3 4 18.5V7.5C4 6.7 4.7 6 5.5 6H10"),
    Music("M9 18V5.5L20 3.5V16M9 18A3 3 0 1 1 3 18A3 3 0 1 1 9 18ZM20 16A3 3 0 1 1 14 16A3 3 0 1 1 20 16Z"),
    Lock("M7.5 11V8A4.5 4.5 0 0 1 16.5 8V11M5.5 11H18.5V20.5H5.5ZM12 14.5V17"),
    Link("M10 14L14 10M8.6 11.4L6.8 13.2A3.5 3.5 0 0 0 11.8 18.2L13.6 16.4M15.4 12.6L17.2 10.8A3.5 3.5 0 0 0 12.2 5.8L10.4 7.6");

    val path = PathParser().parsePathString(d).toPath()
}

@Composable
fun GlyphIcon(glyph: Glyph, modifier: Modifier = Modifier, tint: Color = LocalTokens.current.ink, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val u = this.size.width / 24f
        scale(u, u, pivot = Offset.Zero) {
            if (glyph.fill) drawPath(glyph.path, tint)
            else drawPath(glyph.path, tint, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** A round button with a glyph, like the settings button. `label` is what screen readers say. */
@Composable
fun RoundButton(
    glyph: Glyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    tint: Color = LocalTokens.current.ink, fill: Color = LocalTokens.current.soft, size: Dp = 44.dp,
) {
    val press = remember { MutableInteractionSource() }
    val scale = pressScale(press, 0.88f)
    Box(
        modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(fill)
            .clickable(press, null, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(glyph, tint = tint) }
}

/** A filter or album choice: a pill that fills with the accent when it's on. */
@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, glyph: Glyph? = null) {
    val t = LocalTokens.current
    val press = remember { MutableInteractionSource() }
    val scale = pressScale(press)
    val fill by animateColorAsState(if (on) t.accent else t.soft, snap(), label = "chip")
    val ink by animateColorAsState(if (on) t.accentInk else t.ink, snap(), label = "chipInk")
    Row(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(fill)
            .clickable(press, null, role = Role.Tab, onClick = onClick).heightIn(min = 38.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        glyph?.let { GlyphIcon(it, tint = ink, size = 16.dp) }
        Text(text, color = ink, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
    }
}

/** A small label, like the "Gaming mode" one. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Text(
        text, modifier.clip(CircleShape).border(1.dp, t.accent, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
        color = t.accent, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1,
    )
}

/** What the card says and how Lodi looks for a save in progress, done or failed. */
data class Look(val pose: Pose, val title: String, val sub: String, val percent: Float? = null, val mono: Boolean = false)

fun look(job: Job, copy: Copy, link: String): Look = when (job) {
    Job.Idle -> Look(Pose.Idle, copy.idle, copy.idleSub)
    is Job.Working -> Look(
        Pose.Full,
        when (job.phase) {
            "helper" -> copy.helper
            "joining" -> copy.joining
            "saving" -> copy.saving(job.percent)
            "sharing" -> copy.sharing
            else -> copy.reading
        },
        if (job.phase == "helper") copy.helperSub else short(link),
        job.percent,
        mono = job.phase != "helper",
    )
    is Job.Done -> Look(
        Pose.Yay,
        when {
            job.failed > 0 -> copy.savedSome(job.names.size, job.failed)
            job.names.size > 1 -> copy.savedMany(job.names.size)
            else -> copy.saved
        },
        job.names.joinToString(", "), mono = true,
    )
    is Job.Failed -> copy.error(job.code, Site.of(link)?.label).let { (title, sub) -> Look(Pose.Oops, title, sub) }
    is Job.Choose -> Look(Pose.Grab, copy.pickTitle, copy.pickSub(job.kinds.size))
}

/** The photos and videos of a post, each with a round checkbox like the PC picker's. Saves the ticked ones. */
@Composable
fun Picker(choose: Job.Choose, copy: Copy, onSave: (String) -> Unit) {
    val t = LocalTokens.current
    var picked by remember(choose) { mutableStateOf(choose.kinds.indices.toSet()) }
    val all = picked.size == choose.kinds.size
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        Text(if (all) copy.pickNone else copy.pickAll, color = t.accent, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            modifier = Modifier.clip(CircleShape).clickable { picked = if (all) emptySet() else choose.kinds.indices.toSet() }.padding(horizontal = 10.dp, vertical = 6.dp))
    }
    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxWidth().heightIn(max = 340.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(choose.kinds.size) { i ->
            PickTile(choose.thumbs.getOrElse(i) { "" }, choose.kinds[i] == "video", i in picked) { picked = if (i in picked) picked - i else picked + i }
        }
    }
    Actions { Pill(copy.saveN(picked.size), { onSave(picked.sorted().joinToString(",")) }, Modifier.weight(1f), enabled = picked.isNotEmpty()) }
}

@Composable
private fun PickTile(thumb: String, video: Boolean, on: Boolean, toggle: () -> Unit) {
    val t = LocalTokens.current
    val image by produceState<ImageBitmap?>(null, thumb) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(thumb, it) }
                val step = generateSequence(1) { it * 2 }.first { maxOf(bounds.outWidth, bounds.outHeight) / it <= 480 }
                BitmapFactory.decodeFile(thumb, BitmapFactory.Options().apply { inSampleSize = step })?.asImageBitmap()
            }.getOrNull()
        }
    }
    val scale by animateFloatAsState(if (on && LocalMotion.current) 0.94f else 1f, snap(), label = "pick")
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.aspectRatio(1f).scale(scale).clip(shape).background(t.soft)
            .then(if (on) Modifier.border(3.dp, t.pick, shape) else Modifier)
            .clickable(onClick = toggle),
    ) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (video) Canvas(Modifier.align(Alignment.BottomStart).padding(8.dp).size(24.dp)) {
            drawCircle(Color.Black.copy(alpha = 0.55f))
            val u = size.width / 24f
            drawPath(Path().apply { moveTo(9.5f * u, 7f * u); lineTo(17.5f * u, 12f * u); lineTo(9.5f * u, 17f * u); close() }, Color.White)
        }
        Canvas(Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp)) {
            val u = size.width / 26f
            if (on) {
                drawCircle(t.pick)
                drawPath(Path().apply { moveTo(7.5f * u, 13.5f * u); lineTo(11.5f * u, 17.5f * u); lineTo(18.5f * u, 9f * u) }, Color.White, style = Stroke(2.6f * u, cap = StrokeCap.Round))
            } else {
                drawCircle(Color.Black.copy(alpha = 0.3f))
                drawCircle(Color.White, style = Stroke(2f * u))
            }
        }
    }
}

/** One save in a list of saves running side by side: what it's doing, its link and a bar. */
@Composable
fun TrayRow(job: Job, link: String, copy: Copy, action: (@Composable RowScope.() -> Unit)? = null) {
    val t = LocalTokens.current
    val look = look(job, copy, link)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(t.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(look.title, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(short(link), fontFamily = GeistMono, fontSize = 11.sp, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            action?.invoke(this)
        }
        if (job is Job.Working) Meter(job.percent)
    }
}

@Composable
fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true) {
    val t = LocalTokens.current
    val press = remember { MutableInteractionSource() }
    val scale = pressScale(press, 0.95f)
    val shape = CircleShape
    Box(
        modifier
            .scale(scale)
            .heightIn(min = 48.dp)
            .clip(shape)
            .then(if (primary) Modifier.background(if (enabled) t.accent else t.accent.copy(alpha = 0.45f)) else Modifier.background(t.surface).border(1.dp, t.line, shape))
            .clickable(interactionSource = press, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (primary) t.accentInk else t.ink, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
    }
}

/** A pill progress bar. A negative percent slides a segment back and forth. */
@Composable
fun Meter(percent: Float, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val width by animateFloatAsState((percent / 100f).coerceIn(0f, 1f), drift(), label = "meter")
    val loop = rememberInfiniteTransition(label = "wait")
    val slide by loop.animateFloat(-0.3f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Reverse), label = "slide")
    Canvas(modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(t.line)) {
        val h = size.height
        if (percent < 0) {
            val w = size.width * 0.32f
            drawLine(t.accent, Offset(slide * size.width, h / 2), Offset(slide * size.width + w, h / 2), h, StrokeCap.Round)
        } else if (width > 0f) {
            drawLine(t.accent, Offset(h / 2, h / 2), Offset((size.width * width).coerceAtLeast(h / 2), h / 2), h, StrokeCap.Round)
        }
    }
}

@Composable
fun SlidersIcon(modifier: Modifier = Modifier) {
    val ink = LocalTokens.current.ink
    Canvas(modifier.size(18.dp)) {
        val u = size.width / 24f
        val line = Stroke(2 * u, cap = StrokeCap.Round)
        drawLine(ink, Offset(4 * u, 7 * u), Offset(14 * u, 7 * u), 2 * u, StrokeCap.Round)
        drawLine(ink, Offset(18 * u, 7 * u), Offset(20 * u, 7 * u), 2 * u, StrokeCap.Round)
        drawLine(ink, Offset(4 * u, 17 * u), Offset(8 * u, 17 * u), 2 * u, StrokeCap.Round)
        drawLine(ink, Offset(12 * u, 17 * u), Offset(20 * u, 17 * u), 2 * u, StrokeCap.Round)
        drawCircle(ink, 2 * u, Offset(16 * u, 7 * u), style = line)
        drawCircle(ink, 2 * u, Offset(10 * u, 17 * u), style = line)
    }
}

/** Lodi with a title and a line under it, the top of every card. */
@Composable
fun Header(look: Look, motion: Boolean, lodi: Boolean = true) {
    val t = LocalTokens.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (lodi) Lodi(look.pose, Modifier.size(64.dp), motion)
        Column(Modifier.weight(1f)) {
            Text(look.title, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = t.ink)
            Spacer(Modifier.height(2.dp))
            Text(
                look.sub,
                fontFamily = if (look.mono) GeistMono else Geist,
                fontSize = if (look.mono) 12.sp else 13.sp,
                color = t.muted,
                maxLines = if (look.mono) 1 else 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val t = LocalTokens.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(t.soft).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        content()
    }
}

@Composable
fun Actions(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val t = LocalTokens.current
    Row(Modifier.clip(CircleShape).background(t.soft).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((value, label) in options) {
            val on = value == selected
            Box(
                Modifier.clip(CircleShape).background(if (on) t.accent else t.soft).clickable { onSelect(value) }
                    .heightIn(min = 36.dp).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) t.accentInk else t.ink, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}
