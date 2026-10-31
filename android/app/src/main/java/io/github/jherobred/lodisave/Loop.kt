package io.github.jherobred.lodisave

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Whether grids play their videos and GIFs, silent and on a loop: the Settings switch. */
val LocalLoops = compositionLocalOf { true }

/** Grid videos playing at once. Each holds one of the phone's few video decoders, so the rest show
 *  their thumbnail until one scrolls away. */
private object Slots {
    const val MAX = 6
    var used by mutableIntStateOf(0)
}

/** A grid tile's video or GIF, playing silently on a loop over its thumbnail while LodiSave shows. */
@Composable
fun Loop(item: Saved, modifier: Modifier = Modifier) {
    if (!LocalLoops.current || item.kind != "video" && item.kind != "gif") return
    val showing = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    if (!showing) return
    if (item.kind == "gif") return LoopGif(item, modifier)
    var held by remember { mutableStateOf(false) }
    LaunchedEffect(Slots.used) { if (!held && Slots.used < Slots.MAX) { Slots.used++; held = true } }
    DisposableEffect(Unit) { onDispose { if (held) Slots.used-- } }
    if (!held) return
    var drawn by remember { mutableStateOf(false) }
    // Hidden until its first frame, so the thumbnail shows instead of a black square.
    AndroidView(
        { c -> TextureView(c).apply { surfaceTextureListener = Silent(c, item, this) { drawn = true } } },
        modifier.graphicsLayer { alpha = if (drawn) 1f else 0f },
    )
}

/** A silent, looping MediaPlayer on a TextureView, cropped to fill it the way the thumbnail does. */
private class Silent(val ctx: Context, val item: Saved, val view: TextureView, val onDrawn: () -> Unit) : TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var size = item.width to item.height

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val s = Surface(st).also { surface = it }
        player = MediaPlayer().apply {
            runCatching {
                setDataSource(ctx, item.uri)
                setSurface(s)
                setVolume(0f, 0f)
                isLooping = true
                setOnPreparedListener { it.start() }
                setOnInfoListener { _, what, _ -> if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onDrawn(); false }
                setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) { size = w to h; crop() } }
                setOnErrorListener { _, _, _ -> true }
                prepareAsync()
            }
        }
        crop()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = crop()

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        player?.release()
        player = null
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    // A TextureView stretches the video to its box; scaling it back out crops the middle.
    private fun crop() {
        val (w, h) = size
        if (w <= 0 || h <= 0 || view.width == 0 || view.height == 0) return
        val s = maxOf(view.width.toFloat() / w, view.height.toFloat() / h)
        view.setTransform(Matrix().apply { setScale(w * s / view.width, h * s / view.height, view.width / 2f, view.height / 2f) })
    }
}

/** A GIF decoded small, playing on a loop. Android draws its frames on its own thread. */
@Composable
private fun LoopGif(item: Saved, modifier: Modifier) {
    val ctx = LocalContext.current
    val drawable by produceState<Drawable?>(null, item.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(ctx.contentResolver, item.uri)) { d, info, _ ->
                    val s = 360f / maxOf(info.size.width, info.size.height)
                    if (s < 1f) d.setTargetSize((info.size.width * s).roundToInt().coerceAtLeast(1), (info.size.height * s).roundToInt().coerceAtLeast(1))
                }
            }.getOrNull()
        }
    }
    val d = drawable ?: return
    DisposableEffect(d) { onDispose { (d as? AnimatedImageDrawable)?.stop() } }
    AndroidView({ ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } }, modifier) { v ->
        v.setImageDrawable(d)
        (d as? AnimatedImageDrawable)?.start()
    }
}
