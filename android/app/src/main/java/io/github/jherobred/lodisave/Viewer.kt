package io.github.jherobred.lodisave

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.net.Uri
import android.widget.ImageView
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private val Scrim = Color.Black.copy(alpha = 0.55f)
private val Glass = Color.White.copy(alpha = 0.14f)

/**
 * One save at a time, full screen: swipe between them, pinch or double-tap to zoom photos, play
 * videos, GIFs and audio, and act on the one showing. Photos and videos open in an editor that
 * saves a copy. `ids` are the saves to page through, looked up in `all`; `trash` pages through the
 * trash. Thumbnails grow from and fly back to the grid it opened `from`, and `onSeen` hears which
 * save shows, for that grid to have it in view.
 */
@Composable
fun Viewer(copy: Copy, start: List<Long>, at: Int, trash: Boolean, from: String, all: Map<Long, Saved>, albums: List<String>, onSeen: (Long) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val motion = LocalMotion.current
    val scope = rememberCoroutineScope()
    val ids = remember { start.toMutableStateList() }
    val items = ids.mapNotNull { all[it] }.filter { it.trashed == trash }
    val pager = rememberPagerState(at.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }
    val item = items.getOrNull(pager.currentPage)
    var chrome by remember { mutableStateOf(true) }
    var more by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Saved?>(null) }
    var edited by remember { mutableStateOf<Saved?>(null) } // still shows while the editor slides away
    var show by remember { mutableStateOf<Long?>(null) } // a saved copy to turn to once it loads
    val snack = remember { SnackbarHostState() }
    val hidden = remember(Library.changes) { Prefs(ctx).hiddenFromHome }
    // Video and GIFs start once the thumbnail has grown into place, and stop as it flies back.
    val screen = LocalScreen.current?.transition
    val settled = screen == null || screen.currentState == EnterExitState.Visible && screen.targetState == EnterExitState.Visible
    LaunchedEffect(item?.id) { item?.let { onSeen(it.id) } }
    // Everything here went to the trash or away: back to where it came from.
    LaunchedEffect(items.isEmpty()) { if (items.isEmpty()) onBack() }
    LaunchedEffect(items.size, show) {
        val at = items.indexOfFirst { it.id == show }
        if (at >= 0) { show = null; pager.animateScrollToPage(at) }
    }
    BackHandler(editing != null) { editing = null }
    fun done(message: String, undo: (suspend () -> Unit)? = null) = scope.launch {
        snack.currentSnackbarData?.dismiss()
        val r = snack.showSnackbar(message, undo?.let { copy.undo }, duration = SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) undo?.invoke()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), pageSpacing = 16.dp, key = { items[it].id }) { page ->
            val shown = items[page]
            val toggle = { chrome = !chrome }
            val hero = "$from${shown.id}"
            when (shown.kind) {
                "photo" -> Photo(shown, hero, toggle)
                "gif" -> Gif(shown, hero, settled, toggle)
                else -> Player(copy, shown, hero, settled && page == pager.settledPage, chrome, toggle)
            }
        }

        val enter = if (motion) fadeIn() else EnterTransition.None
        val exit = if (motion) fadeOut() else ExitTransition.None
        AnimatedVisibility(chrome && item != null, Modifier.align(Alignment.TopCenter), enter = enter, exit = exit) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Scrim, Color.Transparent))).statusBarsPadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(Glyph.Back, copy.back, onBack, tint = Color.White, fill = Glass)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(item?.name.orEmpty(), color = Color.White, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${pager.currentPage + 1} / ${items.size}", color = Color.White.copy(alpha = 0.7f), fontFamily = GeistMono, fontSize = 12.sp)
                }
                RoundButton(Glyph.Info, copy.info, { info = true }, tint = Color.White, fill = Glass)
            }
        }
        AnimatedVisibility(chrome && item != null, Modifier.align(Alignment.BottomCenter), enter = enter, exit = exit) {
            val it = item ?: return@AnimatedVisibility
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Scrim))).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
            ) {
                if (it.trashed) {
                    RoundButton(Glyph.Restore, copy.restore, { scope.launch { Library.each(listOf(it)) { s -> Library.trash(ctx, s, false) }; done(copy.restored(1)) } }, tint = Color.White, fill = Glass)
                    RoundButton(Glyph.Trash, copy.deleteForever, { asking = true }, tint = Dark.bad, fill = Glass)
                } else {
                    RoundButton(Glyph.Share, copy.share, { share(ctx, listOf(it)) }, tint = Color.White, fill = Glass)
                    RoundButton(if (it.favorite) Glyph.HeartFull else Glyph.Heart, if (it.favorite) copy.unfavorite else copy.favorite,
                        { scope.launch { Library.each(listOf(it)) { s -> Library.favorite(ctx, s, !it.favorite) } } }, tint = if (it.favorite) Dark.accent else Color.White, fill = Glass)
                    if (it.kind == "photo" || it.kind == "video") RoundButton(Glyph.Edit, copy.edit, { editing = it; edited = it }, tint = Color.White, fill = Glass)
                    RoundButton(Glyph.Trash, copy.delete, { trashWithUndo(scope, ctx, copy, listOf(it), ::done) }, tint = Color.White, fill = Glass)
                    Box {
                        RoundButton(Glyph.More, copy.more, { more = true }, tint = Color.White, fill = Glass)
                        DropdownMenu(more, { more = false }, containerColor = t.surface) {
                            @Composable
                            fun Choice(glyph: Glyph, label: String, onClick: () -> Unit) = DropdownMenuItem(
                                { Text(label, fontFamily = Geist, color = t.ink) }, { more = false; onClick() }, leadingIcon = { GlyphIcon(glyph, size = 20.dp) },
                            )
                            Choice(Glyph.Open, copy.openWith) { openSaved(ctx, it.uri, it.mime, choose = true) }
                            Choice(Glyph.Edit, copy.rename) { naming = true }
                            Choice(Glyph.Folder, copy.moveTo) { moving = true }
                            val off = "${it.id}" in hidden
                            Choice(if (off) Glyph.Eye else Glyph.EyeOff, if (off) copy.showOnHome else copy.hideFromHome) { Library.hide(ctx, listOf(it), !off) }
                        }
                    }
                }
            }
        }
        LodiSnackbar(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 84.dp))

        AnimatedVisibility(
            editing != null,
            enter = if (motion) slideInVertically(floaty()) { it / 3 } + fadeIn() else EnterTransition.None,
            exit = if (motion) slideOutVertically(snap()) { it / 3 } + fadeOut() else ExitTransition.None,
        ) {
            val e = edited ?: return@AnimatedVisibility
            val close = { editing = null }
            val saved = { id: Long ->
                editing = null
                // The copy shows next to its original once the library has it.
                ids.add(ids.indexOf(e.id) + 1, id)
                show = id
                done(copy.savedCopy)
                Unit
            }
            if (e.kind == "photo") EditPhoto(copy, e, close, saved) else TrimVideo(copy, e, close, saved)
        }
    }

    if (info) item?.let { InfoSheet(copy, it) { info = false } }
    if (naming) item?.let { it ->
        NameDialog(copy, copy.rename, it.name.substringBeforeLast('.'), { naming = false }, hint = copy.infoName) { typed ->
            naming = false
            val clean = Library.clean(typed)
            if (clean.isNotEmpty()) scope.launch { if (Library.each(listOf(it)) { s -> Library.rename(ctx, s, clean) } > 0) done(copy.notDone(1)) }
        }
    }
    if (moving) item?.let { it ->
        AlbumSheet(copy, albums, { moving = false }) { to ->
            moving = false
            scope.launch { done(if (Library.each(listOf(it)) { s -> Library.move(ctx, s, to) } > 0) copy.notDone(1) else copy.moved(1, to.ifEmpty { copy.noAlbum })) }
        }
    }
    if (asking) item?.let { it ->
        Confirm(copy, copy.forGood(1), { asking = false }) {
            asking = false
            scope.launch { Library.each(listOf(it)) { s -> Library.delete(ctx, s) } }
        }
    }
}

/** Width over height as it shows, from MediaStore, else from its picture. */
private fun ratioOf(item: Saved, picture: ImageBitmap?): Float = when {
    item.width > 0 && item.height > 0 -> item.width.toFloat() / item.height
    picture != null -> picture.width.toFloat() / picture.height
    else -> 1f
}

/** Decodes a photo no bigger than `max` pixels a side. `editable` gives a bitmap code can draw from. */
private fun decode(ctx: Context, uri: Uri, max: Int, editable: Boolean = false): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
        val scale = max.toFloat() / maxOf(info.size.width, info.size.height)
        if (scale < 1f) d.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
        if (editable) d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

/** A photo that pinches and double-taps to zoom, and pans while zoomed. At 1x a swipe turns the page. */
@Composable
private fun Photo(item: Saved, hero: String, onTap: () -> Unit) {
    val ctx = LocalContext.current
    val thumb = rememberThumb(item)
    val full by produceState<ImageBitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { runCatching { decode(ctx, item.uri, 2560).asImageBitmap() }.getOrNull() }
    }
    val ratio = ratioOf(item, full ?: thumb)
    val scope = rememberCoroutineScope()
    val zoom = remember { Animatable(1f) }
    val pan = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    // How far a zoomed photo can move before its edge leaves the screen's.
    fun limit(o: Offset, s: Float): Offset {
        val w = minOf(box.width.toFloat(), box.height * ratio)
        val h = w / ratio
        val x = ((w * s - box.width) / 2).coerceAtLeast(0f)
        val y = ((h * s - box.height) / 2).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-x, x), o.y.coerceIn(-y, y))
    }
    Box(
        Modifier.fillMaxSize().onSizeChanged { box = it }
            .pointerInput(item.id) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = { p ->
                    val to = if (zoom.value > 1.05f) 1f else 2.5f
                    val goal = if (to == 1f) Offset.Zero else limit((p - Offset(box.width / 2f, box.height / 2f)) * (1 - to), to)
                    scope.launch { zoom.animateTo(to, snap()) }
                    scope.launch { pan.animateTo(goal, snap()) }
                })
            }
            .pointerInput(item.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        // Two fingers zoom; one finger pans a zoomed photo and leaves the page swipe alone at 1x.
                        if (event.changes.count { it.pressed } > 1 || zoom.value > 1.01f) {
                            val s = (zoom.value * event.calculateZoom()).coerceIn(1f, 5f)
                            val o = limit(pan.value + event.calculatePan(), s)
                            scope.launch { zoom.snapTo(s); pan.snapTo(o) }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Hero(hero, Modifier.aspectRatio(ratio).graphicsLayer { scaleX = zoom.value; scaleY = zoom.value; translationX = pan.value.x; translationY = pan.value.y }) {
            (full ?: thumb)?.let { Image(it, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
    }
}

/** A GIF, playing once it's in place. Android draws its frames itself. */
@Composable
private fun Gif(item: Saved, hero: String, settled: Boolean, onTap: () -> Unit) {
    val ctx = LocalContext.current
    val thumb = rememberThumb(item)
    val drawable by produceState<Drawable?>(null, item.id) {
        value = withContext(Dispatchers.IO) { runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(ctx.contentResolver, item.uri)) }.getOrNull() }
    }
    DisposableEffect(drawable) { onDispose { (drawable as? AnimatedImageDrawable)?.stop() } }
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onTap() } }, contentAlignment = Alignment.Center) {
        Hero(hero, Modifier.aspectRatio(ratioOf(item, thumb))) {
            thumb?.let { Image(it, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            val d = drawable
            if (d != null && settled) AndroidView({ ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_XY } }, Modifier.fillMaxSize()) { v ->
                v.setImageDrawable(d)
                (d as? AnimatedImageDrawable)?.start()
            }
        }
    }
}

/** A video or audio file, playing while it's the page in front, with play, pause and a seek bar. */
@Composable
private fun Player(copy: Copy, item: Saved, hero: String, active: Boolean, chrome: Boolean, onTap: () -> Unit) {
    val t = LocalTokens.current
    val thumb = rememberThumb(item)
    var view by remember { mutableStateOf<VideoView?>(null) }
    var drawn by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var length by remember { mutableLongStateOf(item.duration) }
    val video = item.kind == "video"
    LaunchedEffect(view) {
        val v = view ?: return@LaunchedEffect
        while (true) {
            position = v.currentPosition.toLong()
            playing = v.isPlaying
            delay(200)
        }
    }
    LaunchedEffect(active) { if (!active) drawn = false }
    val player: @Composable (Modifier) -> Unit = { modifier ->
        if (active) AndroidView(
            { c ->
                VideoView(c).apply {
                    setOnPreparedListener { mp -> mp.isLooping = true; length = mp.duration.toLong(); start() }
                    setOnInfoListener { _, what, _ -> if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) drawn = true; false }
                    setVideoURI(item.uri)
                    view = this
                }
            },
            modifier, onRelease = { it.stopPlayback(); view = null },
        )
    }
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onTap() } }, contentAlignment = Alignment.Center) {
        if (video) Hero(hero, Modifier.aspectRatio(ratioOf(item, thumb))) {
            player(Modifier.fillMaxSize())
            // The thumbnail covers the video until its first frame is drawn.
            if (!drawn) thumb?.let { Image(it, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Hero(hero, Modifier.size(220.dp), RoundedCornerShape(36.dp)) {
                Box(Modifier.fillMaxSize().background(t.surface), contentAlignment = Alignment.Center) {
                    thumb?.let { Image(it, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: GlyphIcon(Glyph.Music, tint = t.muted, size = 72.dp)
                }
            }
            Text(item.name, Modifier.padding(horizontal = 32.dp), color = Color.White, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // Audio needs no picture, only the player.
            player(Modifier.size(1.dp))
        }
        AnimatedVisibility(chrome && active, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp), enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoundButton(if (playing) Glyph.Pause else Glyph.Play, if (playing) copy.pause else copy.play,
                    { view?.let { if (it.isPlaying) it.pause() else it.start() } }, tint = Color.White, fill = Glass, size = 40.dp)
                Slider(
                    if (length > 0) position.toFloat() / length else 0f, { f -> position = (f * length).toLong(); view?.seekTo(position.toInt()) },
                    Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = Dark.accent, activeTrackColor = Dark.accent, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                )
                Text("${clock(position)} / ${clock(length)}", color = Color.White, fontFamily = GeistMono, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun InfoSheet(copy: Copy, item: Saved, onDismiss: () -> Unit) {
    val t = LocalTokens.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = t.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(copy.info, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = t.ink)
            val rows = listOfNotNull(
                copy.infoName to item.name,
                copy.infoType to "${copy.kind(item.kind)} · ${item.mime}",
                if (item.width > 0 && item.height > 0) copy.infoSize to "${item.width} × ${item.height}" else null,
                if (item.duration > 0) copy.infoLength to clock(item.duration) else null,
                copy.infoFile to sizeText(item.bytes),
                copy.infoSaved to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.added * 1000)),
                copy.infoAlbum to item.album.ifEmpty { copy.noAlbum },
                copy.infoFolder to item.path,
            )
            for ((label, value) in rows) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, Modifier.width(96.dp), fontFamily = Geist, fontSize = 14.sp, color = t.muted)
                Text(value, Modifier.weight(1f), fontFamily = if (label == copy.infoFolder) GeistMono else Geist, fontSize = 14.sp, color = t.ink)
            }
        }
    }
}

/** The bar over an editor: close, a title, and Save copy, or a bar while it saves. */
@Composable
private fun EditBar(copy: Copy, title: String, saving: Boolean, onClose: () -> Unit, onSave: () -> Unit) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RoundButton(Glyph.Close, copy.cancel, onClose)
        Text(title, Modifier.weight(1f), fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = t.ink)
        if (saving) Meter(-1f, Modifier.width(96.dp)) else Pill(copy.saveCopy, onSave)
    }
}

private val RATIOS = listOf("free" to null, "1:1" to 1f, "4:5" to 0.8f, "16:9" to 16f / 9f)

/** Rotate, flip and crop a photo, then save it as a copy beside the original. */
@Composable
private fun EditPhoto(copy: Copy, item: Saved, onClose: () -> Unit, onSaved: (Long) -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    var turns by remember { mutableIntStateOf(0) } // quarter turns clockwise, then the flip
    var flipped by remember { mutableStateOf(false) }
    var ratio by remember { mutableStateOf("free") }
    var crop by remember { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    var saving by remember { mutableStateOf(false) }
    val source by produceState<Bitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { runCatching { decode(ctx, item.uri, 1600, editable = true) }.getOrNull() }
    }
    val shown = remember(source, turns, flipped) { source?.let { turned(it, turns, flipped).asImageBitmap() } }
    val aspect = shown?.let { it.width.toFloat() / it.height } ?: 1f
    fun fit(key: String) {
        ratio = key
        val r = RATIOS.first { it.first == key }.second ?: return run { crop = Rect(0f, 0f, 1f, 1f) }
        // The biggest crop of that shape, in the middle.
        val w = minOf(1f, r / aspect)
        val h = minOf(1f, aspect / r)
        crop = Rect((1 - w) / 2, (1 - h) / 2, (1 + w) / 2, (1 + h) / 2)
    }
    fun save() {
        saving = true
        scope.launch {
            val id = withContext(Dispatchers.IO) {
                runCatching {
                    // ponytail: 4096 px a side at most, which keeps a big photo's copy from running out of memory
                    val full = turned(decode(ctx, item.uri, 4096, editable = true), turns, flipped)
                    val c = crop
                    val x = (c.left * full.width).roundToInt().coerceIn(0, full.width - 1)
                    val y = (c.top * full.height).roundToInt().coerceIn(0, full.height - 1)
                    val out = Bitmap.createBitmap(full, x, y, (c.width * full.width).roundToInt().coerceIn(1, full.width - x), (c.height * full.height).roundToInt().coerceIn(1, full.height - y))
                    val png = item.mime == "image/png"
                    val name = "${item.name.substringBeforeLast('.')} (edit).${if (png) "png" else "jpg"}"
                    val uri = Saver.publish(ctx, name, if (png) "image/png" else "image/jpeg", item.album) { out.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it) }
                    ContentUris.parseId(uri)
                }.getOrNull()
            }
            saving = false
            if (id != null) { Library.changed(); onSaved(id) } else android.widget.Toast.makeText(ctx, copy.cantEdit, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    Column(Modifier.fillMaxSize().background(t.ground).safeDrawingPadding()) {
        EditBar(copy, copy.edit, saving, onClose, ::save)
        Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            val image = shown
            if (image == null) Meter(-1f, Modifier.width(120.dp))
            else Box(Modifier.aspectRatio(aspect)) {
                Image(image, item.name, Modifier.fillMaxSize())
                CropFrame(crop, RATIOS.first { it.first == ratio }.second, aspect) { crop = it }
            }
        }
        LazyRow(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(RATIOS) { (key, _) -> Chip(if (key == "free") copy.free else key, ratio == key, { fit(key) }) }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // Turning a flipped photo the other way round turns what shows clockwise.
            RoundButton(Glyph.Rotate, copy.rotate, { turns = (turns + if (flipped) 3 else 1) % 4; fit(ratio) })
            RoundButton(Glyph.Flip, copy.flip, { flipped = !flipped; fit(ratio) })
            Spacer(Modifier.weight(1f))
            Pill(copy.reset, { turns = 0; flipped = false; fit("free") }, primary = false)
        }
    }
}

private fun turned(b: Bitmap, turns: Int, flipped: Boolean): Bitmap {
    if (turns == 0 && !flipped) return b
    val m = Matrix().apply {
        postRotate(turns * 90f)
        if (flipped) postScale(-1f, 1f)
    }
    return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
}

/** The crop over a photo, in fractions of it: drag a corner to size it, inside it to move it. A
 *  `ratio` keeps its shape, width over height in pixels of a photo `aspect` wide. */
@Composable
private fun CropFrame(crop: Rect, ratio: Float?, aspect: Float, onCrop: (Rect) -> Unit) {
    val now by rememberUpdatedState(crop)
    Canvas(
        Modifier.fillMaxSize().pointerInput(ratio, aspect) {
            var grip = -1 // 0..3 a corner from the top left, clockwise; 4 the whole frame
            detectDragGestures(
                onDragStart = { p ->
                    val c = now
                    val corners = listOf(c.topLeft, c.topRight, c.bottomRight, c.bottomLeft).map { Offset(it.x * size.width, it.y * size.height) }
                    val near = corners.indices.minBy { hypot(corners[it].x - p.x, corners[it].y - p.y) }
                    grip = when {
                        hypot(corners[near].x - p.x, corners[near].y - p.y) < 40.dp.toPx() -> near
                        Rect(corners[0], corners[2]).contains(p) -> 4
                        else -> -1
                    }
                },
                onDrag = { change, d ->
                    if (grip < 0) return@detectDragGestures
                    change.consume()
                    onCrop(dragged(now, grip, d.x / size.width, d.y / size.height, ratio, aspect))
                },
            )
        },
    ) {
        val r = Rect(crop.left * size.width, crop.top * size.height, crop.right * size.width, crop.bottom * size.height)
        val dim = Color.Black.copy(alpha = 0.55f)
        drawRect(dim, Offset.Zero, Size(size.width, r.top))
        drawRect(dim, Offset(0f, r.bottom), Size(size.width, size.height - r.bottom))
        drawRect(dim, Offset(0f, r.top), Size(r.left, r.height))
        drawRect(dim, Offset(r.right, r.top), Size(size.width - r.right, r.height))
        for (i in 1..2) {
            drawLine(Color.White.copy(alpha = 0.4f), Offset(r.left + r.width * i / 3, r.top), Offset(r.left + r.width * i / 3, r.bottom), 1.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.4f), Offset(r.left, r.top + r.height * i / 3), Offset(r.right, r.top + r.height * i / 3), 1.dp.toPx())
        }
        drawRect(Color.White, r.topLeft, r.size, style = Stroke(2.dp.toPx()))
        val arm = 18.dp.toPx()
        val w = 4.dp.toPx()
        for ((corner, dx, dy) in listOf(Triple(r.topLeft, 1f, 1f), Triple(r.topRight, -1f, 1f), Triple(r.bottomRight, -1f, -1f), Triple(r.bottomLeft, 1f, -1f))) {
            drawLine(Color.White, corner, corner + Offset(arm * dx, 0f), w, StrokeCap.Round)
            drawLine(Color.White, corner, corner + Offset(0f, arm * dy), w, StrokeCap.Round)
        }
    }
}

/** A crop after dragging `grip` by a fraction of the photo, kept inside it and, with a `ratio`, to its shape. */
private fun dragged(c: Rect, grip: Int, dx: Float, dy: Float, ratio: Float?, aspect: Float): Rect {
    val min = 0.08f
    if (grip == 4) {
        val x = (c.left + dx).coerceIn(0f, 1f - c.width)
        val y = (c.top + dy).coerceIn(0f, 1f - c.height)
        return Rect(x, y, x + c.width, y + c.height)
    }
    val left = grip == 0 || grip == 3
    val top = grip == 0 || grip == 1
    // The corner across from the one dragged stays put.
    val ax = if (left) c.right else c.left
    val ay = if (top) c.bottom else c.top
    val x = ((if (left) c.left else c.right) + dx).coerceIn(0f, 1f)
    val y = ((if (top) c.top else c.bottom) + dy).coerceIn(0f, 1f)
    val roomW = if (left) ax else 1f - ax
    val roomH = if (top) ay else 1f - ay
    var w = abs(x - ax).coerceIn(min, roomW)
    var h = abs(y - ay).coerceIn(min, roomH)
    if (ratio != null) {
        // In fractions, w * aspect / h is the crop's shape in pixels.
        h = w * aspect / ratio
        if (h > roomH) { h = roomH; w = h * ratio / aspect }
    }
    val l = if (left) ax - w else ax
    val tp = if (top) ay - h else ay
    return Rect(l, tp, l + w, tp + h)
}

/** Trims a video's start and end, without re-encoding, and saves the cut as a copy. */
@Composable
private fun TrimVideo(copy: Copy, item: Saved, onClose: () -> Unit, onSaved: (Long) -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<VideoView?>(null) }
    var length by remember { mutableLongStateOf(item.duration) }
    var range by remember { mutableStateOf(0f..1f) }
    var saving by remember { mutableStateOf(false) }
    val from = (range.start * length).toLong()
    val to = (range.endInclusive * length).toLong()
    // Plays the part being kept, over and over.
    LaunchedEffect(view, from, to) {
        val v = view ?: return@LaunchedEffect
        while (true) {
            if (v.isPlaying && v.currentPosition > to) v.seekTo(from.toInt())
            delay(100)
        }
    }
    fun save() {
        saving = true
        view?.pause()
        scope.launch {
            val id = withContext(Dispatchers.IO) {
                val webm = item.mime == "video/webm"
                val name = "${item.name.substringBeforeLast('.')} (trim).${if (webm) "webm" else "mp4"}"
                val out = File(ctx.cacheDir, "trim-${item.id}").apply { mkdirs() }.resolve(name)
                try {
                    if (!Saver.trim(ctx, item.uri, from * 1000, to * 1000, out, webm)) null
                    else ContentUris.parseId(Saver.publish(ctx, out, item.album))
                } catch (e: Exception) {
                    null
                } finally {
                    out.parentFile?.deleteRecursively()
                }
            }
            saving = false
            if (id != null) { Library.changed(); onSaved(id) } else android.widget.Toast.makeText(ctx, copy.cantEdit, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    Column(Modifier.fillMaxSize().background(t.ground).safeDrawingPadding()) {
        EditBar(copy, copy.trim, saving, onClose, ::save)
        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(24.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                { c ->
                    VideoView(c).apply {
                        setOnPreparedListener { mp -> mp.isLooping = true; if (length <= 0) length = mp.duration.toLong(); start() }
                        setVideoURI(item.uri)
                        view = this
                    }
                },
                Modifier.aspectRatio(ratioOf(item, null)), onRelease = { it.stopPlayback(); view = null },
            )
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text("${copy.trimStart} ${clock(from)}", Modifier.weight(1f), fontFamily = GeistMono, fontSize = 13.sp, color = t.ink)
                Text(clock(to - from), fontFamily = GeistMono, fontSize = 13.sp, color = t.accent)
                Text("${copy.trimEnd} ${clock(to)}", Modifier.weight(1f), fontFamily = GeistMono, fontSize = 13.sp, color = t.ink, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            RangeSlider(
                range,
                { r ->
                    // Shows the frame at the end being moved.
                    val moved = if (r.start != range.start) r.start else r.endInclusive
                    range = r
                    view?.pause()
                    view?.seekTo((moved * length).toInt())
                },
                enabled = length > 0 && !saving,
                onValueChangeFinished = { view?.seekTo(from.toInt()); view?.start() },
                colors = SliderDefaults.colors(thumbColor = t.accent, activeTrackColor = t.accent, inactiveTrackColor = t.line),
            )
            Text(copy.trimNote, fontFamily = Geist, fontSize = 12.sp, color = t.muted)
        }
    }
}
