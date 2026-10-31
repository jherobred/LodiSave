package io.github.jherobred.lodisave

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import androidx.annotation.OptIn
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
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Whether grids play their videos and GIFs, silent and on a loop: the Settings switch. */
val LocalLoops = compositionLocalOf { true }

/** Grid videos playing at once: every tile a phone screen shows. Once the phone's video decoders are
 *  all busy, the rest play their small previews with Android's own software decoder. */
private object Slots {
    const val MAX = 18
    var used by mutableIntStateOf(0)
}

/**
 * Small silent copies of grid videos: at most 480 pixels on the short side, 24 frames a second and
 * 15 seconds, made once by the phone's own encoder and kept in the cache. A full-size video takes a
 * whole decoder to play, so a grid of them lags; a dozen previews cost the phone about as much as two.
 */
@OptIn(UnstableApi::class)
object Previews {
    private const val SHORT = 480
    private const val LONG_MS = 15_000L
    private const val KEEP = 400L shl 20
    private val one = Mutex() // the encoder makes one at a time
    private val main = Handler(Looper.getMainLooper())
    private val failed = mutableMapOf<Long, Int>() // tries that failed, by video

    private fun dir(ctx: Context) = File(ctx.cacheDir, "previews").apply { mkdirs() }

    /** What a tile plays: the video itself when it's small and short already, or its preview, made
     *  first if it has to be. Null when the phone couldn't make one. */
    suspend fun of(ctx: Context, item: Saved): Uri? {
        if (minOf(item.width, item.height) in 1..SHORT && item.duration in 1..LONG_MS) return item.uri
        if ((failed[item.id] ?: 0) >= 3) return null
        // The size in the name makes a new preview after the video is edited.
        val file = File(dir(ctx), "${item.id}-${item.bytes}.mp4")
        if (file.length() == 0L) one.withLock {
            if (file.length() > 0) return@withLock
            val part = File(file.path + ".part")
            // Every decoder can be busy playing other tiles for a moment, so a failed try waits and goes again.
            while (!make(ctx, item, part) || !part.renameTo(file)) {
                part.delete()
                failed[item.id] = (failed[item.id] ?: 0) + 1
                if (failed.getValue(item.id) >= 3) return null
                delay(2000)
            }
            trim(ctx)
        }
        file.setLastModified(System.currentTimeMillis())
        return Uri.fromFile(file)
    }

    private suspend fun make(ctx: Context, item: Saved, out: File): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { done ->
            val small = minOf(item.width, item.height)
            val effects = listOfNotNull(
                FrameDropEffect.createDefaultFrameDropEffect(24f),
                if (small == 0 || small > SHORT) Presentation.createForShortSide(SHORT) else null,
            )
            val media = MediaItem.Builder().setUri(item.uri)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(LONG_MS).build()).build()
            val edited = EditedMediaItem.Builder(media).setRemoveAudio(true).setEffects(Effects(emptyList(), effects)).build()
            // With every hardware decoder busy playing tiles, the copy is made with the software one.
            val decoders = DefaultDecoderFactory.Builder(ctx).setEnableDecoderFallback(true).build()
            val transformer = Transformer.Builder(ctx)
                .setAssetLoaderFactory(DefaultAssetLoaderFactory(ctx, decoders, Clock.DEFAULT, DefaultMediaSourceFactory(ctx), DataSourceBitmapLoader(ctx)))
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(DefaultEncoderFactory.Builder(ctx)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(900_000).build()).build())
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        if (done.isActive) done.resume(true)
                    }

                    override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                        android.util.Log.w("LodiPreview", "${item.name}: $e")
                        if (done.isActive) done.resume(false)
                    }
                })
                .build()
            transformer.start(edited, out.path)
            // A tile that scrolled away before its preview was done: the encoder moves on to the next.
            done.invokeOnCancellation { main.post { transformer.cancel(); out.delete() } }
        }
    }

    /** Keeps the newest previews, up to [KEEP] bytes. */
    private fun trim(ctx: Context) {
        var total = 0L
        for (f in dir(ctx).listFiles().orEmpty().sortedByDescending { it.lastModified() }) {
            total += f.length()
            if (total > KEEP || f.name.endsWith(".part")) f.delete()
        }
    }
}

/** A grid tile's video or GIF, playing silently on a loop over its thumbnail while LodiSave shows. */
@Composable
fun Loop(item: Saved, modifier: Modifier = Modifier) {
    if (!LocalLoops.current || item.kind != "video" && item.kind != "gif") return
    val showing = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    if (!showing) return
    if (item.kind == "gif") return LoopGif(item, modifier)
    val ctx = LocalContext.current
    val source by produceState<Uri?>(null, item.id, item.bytes) { value = Previews.of(ctx, item) }
    val uri = source ?: return
    var held by remember { mutableStateOf(false) }
    LaunchedEffect(Slots.used) { if (!held && Slots.used < Slots.MAX) { Slots.used++; held = true } }
    DisposableEffect(Unit) { onDispose { if (held) Slots.used-- } }
    if (!held) return
    var drawn by remember { mutableStateOf(false) }
    // Hidden until its first frame, so the thumbnail shows instead of a black square.
    AndroidView(
        { c -> TextureView(c).apply { surfaceTextureListener = Silent(c, uri, item, this) { drawn = true } } },
        modifier.graphicsLayer { alpha = if (drawn) 1f else 0f },
    )
}

/** A silent, looping player on a TextureView, cropped to fill it the way the thumbnail does. It falls
 *  back to the software decoder when the hardware ones are all playing other tiles. */
@OptIn(UnstableApi::class)
private class Silent(val ctx: Context, val uri: Uri, item: Saved, val view: TextureView, val onDrawn: () -> Unit) : TextureView.SurfaceTextureListener {
    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var size = item.width to item.height

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val s = Surface(st).also { surface = it }
        player = ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)).build().apply {
            setVideoSurface(s)
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() = onDrawn()
                override fun onVideoSizeChanged(v: VideoSize) { if (v.width > 0 && v.height > 0) { size = v.width to v.height; crop() } }
            })
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
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
