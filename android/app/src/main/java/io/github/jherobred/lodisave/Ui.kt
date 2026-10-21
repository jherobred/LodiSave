package io.github.jherobred.lodisave

import android.content.ClipboardManager
import android.graphics.BitmapFactory
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

fun openSaved(ctx: Context, uri: Uri, mime: String) {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime.ifEmpty { "*/*" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(view) }
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
    is Job.Failed -> copy.error(job.code).let { (title, sub) -> Look(Pose.Oops, title, sub) }
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
    val scale by animateFloatAsState(if (on) 0.94f else 1f, snap(), label = "pick")
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
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, snap(), label = "press")
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
