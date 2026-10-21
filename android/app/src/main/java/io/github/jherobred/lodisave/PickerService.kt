package io.github.jherobred.lodisave

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Picking photos and videos inside other apps, as the PC picker does on web pages. Tap the floating
 * Lodi and a round checkbox shows on each photo and video on screen. Ticking one gets its post's
 * link the way a person would: LodiSave taps the post's Share button, then Copy link. Save saves the
 * original files from those links, the photo you ticked in a carousel, not the whole post.
 *
 * An accessibility service, since that's the only way Android lets one app see where another app's
 * photos are and press its buttons. It looks at the screen only while picking.
 */
class PickerService : AccessibilityService() {
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val one = Mutex() // one Share > Copy link at a time
    private val dp get() = resources.displayMetrics.density
    private val copy get() = Copy.of(this)

    private var picking = false
    private var busy = 0
    private var bar: LinearLayout? = null
    private var status: TextView? = null
    private var saveButton: TextView? = null
    private val circles = mutableListOf<Circle>()
    /** Ticked photos and videos, by where they sit in the app's lists, with the post link and carousel spot. */
    private val picks = LinkedHashMap<String, Pick>()
    private var clip: CompletableDeferred<String?>? = null
    private val rescan = Runnable { render() }

    private class Pick(var state: State, var link: String? = null, var index: Int? = null)
    private enum class State { Fetching, Ready, Failed }
    private class Media(val key: String, val bounds: Rect, val node: AccessibilityNodeInfo)

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        stop()
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() = stop()

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!picking || busy > 0) return
        // LodiSave's own bar and circles change as you pick: only a LodiSave screen being picked from counts.
        if (event.packageName == packageName && rootInActiveWindow?.packageName != packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> { circles.forEach { it.view.alpha = 0f }; later() }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> later()
            else -> {}
        }
    }

    private fun later() {
        bar?.removeCallbacks(rescan)
        bar?.postDelayed(rescan, 140)
    }

    fun toggle() = if (picking) stop() else start()

    private fun start() {
        picking = true
        showBar()
        render()
    }

    private fun stop() {
        picking = false
        picks.clear()
        circles.forEach { runCatching { wm.removeView(it.view) } }
        circles.clear()
        bar?.let { runCatching { wm.removeView(it) } }
        bar = null
    }

    // ----- Finding photos and videos -----

    /** The app being picked from: the active app window, under LodiSave's bar and circles. */
    private fun appRoot(): AccessibilityNodeInfo? =
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }?.root ?: rootInActiveWindow

    private fun scan(): List<Media> {
        val root = appRoot() ?: return emptyList()
        val width = resources.displayMetrics.widthPixels
        val found = mutableListOf<Media>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 80) return
            val r = Rect().also(n::getBoundsInScreen)
            // Photos and videos, not avatars, icons or emoji: at least a third of the screen wide.
            if (n.isVisibleToUser && r.width() >= width * 0.3 && r.height() >= width * 0.2 && isMedia(n)) found += Media(keyOf(n), r, n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        // One circle per spot: a video's frame and the picture inside it count once.
        return found.filter { a -> found.none { b -> b !== a && b.bounds.contains(a.bounds) && (b.bounds != a.bounds || found.indexOf(b) < found.indexOf(a)) } }
    }

    private fun isMedia(n: AccessibilityNodeInfo) =
        MEDIA_CLASS.containsMatchIn(n.className ?: "") || MEDIA_WORDS.containsMatchIn(n.contentDescription ?: "")

    /** Where a photo sits in the app's lists, which stays the same as the feed scrolls. */
    private fun keyOf(n: AccessibilityNodeInfo): String {
        val spots = generateSequence(n) { it.parent }.take(12).mapNotNull { p -> p.collectionItemInfo?.let { "${it.rowIndex}.${it.columnIndex}" } }.toList()
        val r = Rect().also(n::getBoundsInScreen)
        return if (spots.isNotEmpty()) "${n.packageName}|${spots.joinToString("/")}|${n.contentDescription ?: ""}" else "${n.packageName}|${n.contentDescription ?: ""}|${r.top / 40}"
    }

    /** A photo's place in its post's carousel, from the carousel list or a "2/5" counter, or null. */
    private fun carouselIndex(n: AccessibilityNodeInfo): Int? {
        for (p in generateSequence(n) { it.parent }.take(10)) {
            val item = p.collectionItemInfo ?: continue
            val list = p.parent?.collectionInfo ?: continue
            if (list.rowCount <= 1 && list.columnCount != 1) return item.columnIndex
        }
        COUNTER.find(n.contentDescription ?: "")?.let { return it.groupValues[1].toInt() - 1 }
        return null
    }

    private fun render() {
        if (!picking) return
        val media = scan()
        while (circles.size > media.size) runCatching { wm.removeView(circles.removeAt(circles.size - 1).view) }
        while (circles.size < media.size) circles += Circle(this).also { c ->
            wm.addView(c.view, c.params)
            c.view.setOnClickListener { c.media?.let(::tap) }
        }
        val size = (40 * dp).toInt()
        val top = statusBarHeight()
        media.forEachIndexed { i, m ->
            val c = circles[i]
            c.media = m
            c.params.x = (m.bounds.right - size - 6 * dp).toInt()
            c.params.y = maxOf(m.bounds.top + (6 * dp).toInt(), top)
            c.view.state = picks[m.key]?.state
            c.view.alpha = 1f
            runCatching { wm.updateViewLayout(c.view, c.params) }
        }
        updateBar()
    }

    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    private fun statusBarHeight(): Int = resources.getIdentifier("status_bar_height", "dimen", "android").takeIf { it > 0 }?.let(resources::getDimensionPixelSize) ?: (24 * dp).toInt()

    // ----- Ticking: Share, then Copy link -----

    private fun tap(m: Media) {
        val pick = picks[m.key]
        if (pick != null && pick.state != State.Fetching) {
            picks.remove(m.key)
            render()
            return
        }
        if (pick != null) return
        picks[m.key] = Pick(State.Fetching)
        render()
        scope.launch {
            val got = one.withLock { linkOf(m) }
            val p = picks[m.key] ?: return@launch
            if (got == null) {
                p.state = State.Failed
                render()
                status?.text = copy.pickNoLink
                delay(1600)
                picks.remove(m.key)
            } else {
                p.state = State.Ready
                p.link = got.first
                p.index = got.second
            }
            render()
        }
    }

    /** The post's link and the photo's place in it, read by pressing the post's Share, then Copy link. */
    private suspend fun linkOf(m: Media): Pair<String, Int?>? {
        busy++
        try {
            val index = carouselIndex(m.node)
            val share = shareButton(m) ?: return null.also { log("no Share near ${m.key}") }
            if (!click(share)) return null.also { log("Share didn't take the click") }
            // A share sheet can take seconds the first time, while the app loads it and your contacts.
            val copyLink = waitFor(6000) { COPY_LINK.containsMatchIn(label(it).trim()) } ?: run {
                log("no Copy link after Share")
                performGlobalAction(GLOBAL_ACTION_BACK)
                return null
            }
            clip = CompletableDeferred()
            click(copyLink)
            delay(350)
            // Android shares the clipboard only with the app in front, so a see-through screen reads it.
            startActivity(Intent(this, ClipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            val link = withTimeoutOrNull(4000) { clip?.await() }
            log("copied ${link ?: "nothing"} at carousel spot $index")
            delay(250)
            // Some apps leave their share sheet open after copying.
            if (find { COPY_LINK.containsMatchIn(label(it).trim()) } != null) performGlobalAction(GLOBAL_ACTION_BACK)
            return link?.let { it to index }
        } finally {
            clip = null
            busy--
        }
    }

    /** The post's Share button: the one nearest below the photo, in the smallest part of the screen around it that has one. */
    private fun shareButton(m: Media): AccessibilityNodeInfo? {
        val screen = resources.displayMetrics.heightPixels
        for (around in generateSequence(m.node.parent) { it.parent }.take(12)) {
            val found = mutableListOf<AccessibilityNodeInfo>()
            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 40) return
                if (n.isVisibleToUser && (SHARE.containsMatchIn(label(n)) || n.viewIdResourceName?.contains("share", ignoreCase = true) == true)) found += n
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
            }
            walk(around, 0)
            val near = found.map { it to Rect().also(it::getBoundsInScreen) }
                .filter { (_, r) -> r.centerY() >= m.bounds.top && r.centerY() <= m.bounds.bottom + screen * 0.5 }
            if (near.isNotEmpty()) return near.minBy { (_, r) -> maxOf(0, r.top - m.bounds.bottom) }.first
        }
        return null
    }

    private fun label(n: AccessibilityNodeInfo) = (n.contentDescription ?: n.text ?: "").toString()

    private fun log(message: String) = android.util.Log.i("LodiPicker", message)

    private fun click(n: AccessibilityNodeInfo): Boolean =
        generateSequence(n) { it.parent }.take(6).firstOrNull { it.isClickable }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

    private fun find(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        for (root in windows.filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.mapNotNull { it.root }) {
            val stack = ArrayDeque(listOf(root))
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                if (n.isVisibleToUser && match(n)) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let(stack::add)
            }
        }
        return null
    }

    private suspend fun waitFor(ms: Long, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        withTimeoutOrNull(ms) {
            var hit = find(match)
            while (hit == null) {
                delay(150)
                hit = find(match)
            }
            hit
        }

    /** ClipActivity read the clipboard. */
    fun clipped(link: String?) {
        clip?.complete(link)
    }

    // ----- Saving -----

    private fun save() {
        val ready = picks.values.filter { it.state == State.Ready && it.link != null }
        if (ready.isEmpty()) return
        // Photos ticked in one post save together, the ticked ones only.
        val works = ready.groupBy { it.link!! }.map { (link, ps) ->
            val spots = ps.mapNotNull { it.index }
            SaveWorker.enqueue(this, link, if (spots.size == ps.size) spots.distinct().sorted().joinToString(",") else null)
        }
        stop()
        startActivity(Intent(this, ShareActivity::class.java)
            .putExtra(ShareActivity.EXTRA_WORKS, works.map { it.toString() }.toTypedArray())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ----- The bar at the bottom -----

    private fun showBar() {
        if (bar != null) return
        val t = Dark
        fun pill(text: String, fill: Int, ink: Int, onClick: () -> Unit) = TextView(this).apply {
            this.text = text
            setTextColor(ink)
            textSize = 14f
            typeface = resources.getFont(R.font.geist_semibold)
            background = GradientDrawable().apply { cornerRadius = 100 * dp; setColor(fill) }
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            setOnClickListener { onClick() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = 100 * dp; setColor(t.surface.toArgb()) }
            setPadding((18 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            elevation = 8 * dp
        }
        status = TextView(this).apply {
            setTextColor(t.ink.toArgb())
            textSize = 14f
            typeface = resources.getFont(R.font.geist_semibold)
            maxLines = 2
        }
        row.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val gap = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (6 * dp).toInt() } }
        saveButton = pill("", t.accent.toArgb(), t.accentInk.toArgb()) { save() }
        row.addView(saveButton, gap())
        row.addView(pill(copy.pickCopied, t.soft.toArgb(), t.ink.toArgb()) {
            stop()
            startActivity(Intent(this, ShareActivity::class.java).putExtra(ShareActivity.EXTRA_CLIPBOARD, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, gap())
        row.addView(pill("✕", t.soft.toArgb(), t.ink.toArgb()) { stop() }, gap())
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            y = (72 * dp).toInt()
            horizontalMargin = 0.03f
        }
        wm.addView(row, params)
        bar = row
        updateBar()
    }

    private fun updateBar() {
        val ready = picks.values.count { it.state == State.Ready }
        val fetching = picks.values.any { it.state == State.Fetching }
        val says = when {
            fetching -> copy.pickFetching
            ready > 0 -> copy.pickedN(ready)
            else -> copy.pickHint
        }
        // Only on a change: a new text is itself a screen change the picker would answer.
        if (status?.text?.toString() != says) status?.text = says
        saveButton?.apply {
            if (text.toString() != copy.saveN(ready)) text = copy.saveN(ready)
            val shown = if (ready > 0) View.VISIBLE else View.GONE
            if (visibility != shown) visibility = shown
        }
    }

    /** A round checkbox like the PC picker's, sitting on a photo's top-right corner. */
    private class Circle(ctx: Context) {
        val view = Dot(ctx)
        var media: Media? = null
        val params = WindowManager.LayoutParams(
            (40 * ctx.resources.displayMetrics.density).toInt(), (40 * ctx.resources.displayMetrics.density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Placed in screen pixels, where the app's photos are, not pushed down below the status bar.
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private class Dot(ctx: Context) : View(ctx) {
        var state: State? = null
            set(v) { field = v; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tick = Path()
        private val ring = RectF()
        private var spin = 0f

        override fun onDraw(canvas: Canvas) {
            val r = width * 0.36f
            val cx = width / 2f
            val cy = height / 2f
            val u = r / 13f
            when (state) {
                State.Ready -> {
                    paint.style = Paint.Style.FILL; paint.color = Dark.pick.toArgb()
                    canvas.drawCircle(cx, cy, r, paint)
                    paint.style = Paint.Style.STROKE; paint.color = android.graphics.Color.WHITE; paint.strokeWidth = 2.6f * u; paint.strokeCap = Paint.Cap.ROUND
                    tick.rewind()
                    tick.moveTo(cx - 5.5f * u, cy + 0.5f * u); tick.lineTo(cx - 1.5f * u, cy + 4.5f * u); tick.lineTo(cx + 5.5f * u, cy - 4f * u)
                    canvas.drawPath(tick, paint)
                }
                State.Failed -> {
                    paint.style = Paint.Style.FILL; paint.color = Dark.bad.toArgb()
                    canvas.drawCircle(cx, cy, r, paint)
                    paint.color = android.graphics.Color.WHITE; paint.textAlign = Paint.Align.CENTER; paint.textSize = r * 1.3f
                    canvas.drawText("!", cx, cy + r * 0.45f, paint)
                }
                else -> {
                    paint.style = Paint.Style.FILL; paint.color = 0x55000000
                    canvas.drawCircle(cx, cy, r, paint)
                    paint.style = Paint.Style.STROKE; paint.color = android.graphics.Color.WHITE; paint.strokeWidth = 2f * u
                    canvas.drawCircle(cx, cy, r, paint)
                    if (state == State.Fetching) {
                        // Busy getting the link: a turning arc.
                        paint.strokeWidth = 3f * u; paint.color = Dark.pick.toArgb()
                        ring.set(cx - r, cy - r, cx + r, cy + r)
                        canvas.drawArc(ring, spin, 100f, false, paint)
                        spin = (spin + 12f) % 360f
                        postInvalidateOnAnimation()
                    }
                }
            }
        }
    }

    companion object {
        // The running service, set while Android has it bound and cleared when it goes.
        @SuppressLint("StaticFieldLeak")
        var instance: PickerService? = null
            private set

        private val MEDIA_CLASS = Regex("""(ImageView|VideoView|TextureView|SurfaceView|PlayerView)$""")
        private val MEDIA_WORDS = Regex("""\b(photo|image|picture|video|reel|gif|larawan|litrato|bidyo)\b""", RegexOption.IGNORE_CASE)
        private val SHARE = Regex("""^(share|send|share post|send post|share via|i-share|ibahagi|ipadala|compartir|enviar|partager|teilen)\b""", RegexOption.IGNORE_CASE)
        private val COPY_LINK = Regex("""^(copy(\s+(link|url))?|kopyahin(\s+ang\s+link)?|i-copy\s+ang\s+link|copiar\s+(enlace|link)|copier\s+le\s+lien|link\s+kopieren)\b""", RegexOption.IGNORE_CASE)
        private val COUNTER = Regex("""(\d+)\s*(?:/|of|sa)\s*(\d+)""", RegexOption.IGNORE_CASE)

        /** Whether the user turned LodiSave's picker on in Android's accessibility settings. */
        fun enabled(ctx: Context): Boolean {
            val on = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val me = ComponentName(ctx, PickerService::class.java)
            return on.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
