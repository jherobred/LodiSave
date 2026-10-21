package io.github.jherobred.lodisave

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Picking photos and videos inside other apps, as the PC picker does on web pages. Tap the floating
 * Lodi and a round checkbox shows on each photo and video on screen. Ticking one finds the best copy
 * it can: on a web page the photo's own file, which browsers name; in an app its post's link, got the
 * way a person would (LodiSave taps the post's Share button, then Copy link), so Save saves the
 * original files, the photo you ticked in a carousel, not the whole post. Each tick also keeps a shot
 * of the photo as it shows on screen, saved instead when there's no link or the link gives nothing,
 * so a tick saves something in every app.
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
    /** Ticked photos and videos, by where they sit in the app's lists, with what saves them. */
    private val picks = LinkedHashMap<String, Pick>()
    private val rescan = Runnable { render() }

    /** A tick: its post's link and carousel spot, or a web photo's own file (`file`) and the `page`
     *  it's on, and the shot of it on screen. */
    private class Pick(var state: State, val link: String? = null, val index: Int? = null, val file: Boolean = false, val page: String? = null, val shot: File? = null)
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
        // Shots of ticks that were never saved.
        File(cacheDir, "picks").listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.deleteRecursively() }
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
        // Inside first, answering whether the node or anything in it shows text. A picture that an app
        // draws with no node of its own, as Reddit, Pinterest and YouTube do, shows only as the frame
        // around it with no text inside, which counts too.
        fun walk(n: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > 80) return true
            // Off screen, like most of a long web page, and what's inside it: skipped, which keeps big pages quick.
            if (!n.isVisibleToUser) return false
            var words = !n.text.isNullOrBlank()
            for (i in 0 until n.childCount) n.getChild(i)?.let { if (walk(it, depth + 1)) words = true }
            val r = Rect().also(n::getBoundsInScreen)
            // Photos and videos, not avatars, icons or emoji: at least a third of the screen wide.
            if (r.width() >= width * 0.3 && r.height() >= width * 0.2 &&
                (isMedia(n) || !words && depth > 0 && !n.isScrollable && !n.isEditable)) found += Media(keyOf(n), r, n)
            return words
        }
        walk(root, 0)
        // One circle per spot, on the innermost: a video's frame and the picture inside it count
        // once, and a row of photos with no words gets one each.
        return found.filter { a -> found.none { b -> b !== a && a.bounds.contains(b.bounds) && (a.bounds != b.bounds || found.indexOf(b) < found.indexOf(a)) } }
    }

    private fun isMedia(n: AccessibilityNodeInfo) =
        MEDIA_CLASS.containsMatchIn(n.className ?: "") || MEDIA_WORDS.containsMatchIn(n.contentDescription ?: "") ||
            MEDIA_ROLE.matches(n.extras.getCharSequence(CHROME_ROLE) ?: "") ||
            n.text.isNullOrEmpty() && MEDIA_ID.containsMatchIn(n.viewIdResourceName?.substringAfter(":id/") ?: "")

    /** Where a photo sits in the app's lists, which stays the same as the feed scrolls. */
    private fun keyOf(n: AccessibilityNodeInfo): String {
        val spots = generateSequence(n) { it.parent }.take(12).mapNotNull { p -> p.collectionItemInfo?.let { "${it.rowIndex}.${it.columnIndex}" } }.toList()
        val r = Rect().also(n::getBoundsInScreen)
        return if (spots.isNotEmpty()) "${n.packageName}|${spots.joinToString("/")}|${n.contentDescription ?: ""}" else "${n.packageName}|${n.contentDescription ?: ""}|${r.top / 40}"
    }

    /** A photo's place in its post's carousel, from the carousel list or a "2 of 5" counter on it or
     *  just around it (Instagram's is on the frame), or null. */
    private fun carouselIndex(n: AccessibilityNodeInfo): Int? {
        for (p in generateSequence(n) { it.parent }.take(10)) {
            val item = p.collectionItemInfo ?: continue
            val list = p.parent?.collectionInfo ?: continue
            if (list.rowCount <= 1 && list.columnCount != 1) return item.columnIndex
        }
        for (p in generateSequence(n) { it.parent }.take(3)) COUNTER.find(p.contentDescription ?: "")?.let { return it.groupValues[1].toInt() - 1 }
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

    // ----- Ticking: a shot, then the photo's file or its post's link -----

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
            val got = one.withLock { grab(m) }
            val p = picks[m.key] ?: return@launch
            if (got.link == null && got.shot == null) {
                p.state = State.Failed
                render()
                status?.text = copy.pickNoLink
                delay(1600)
                picks.remove(m.key)
            } else {
                picks[m.key] = got
            }
            render()
        }
    }

    /** What saving a ticked photo takes: its shot first, while it's on screen, then its best link. */
    private suspend fun grab(m: Media): Pick {
        busy++
        try {
            val shot = if (Build.VERSION.SDK_INT >= 30) runCatching { shoot(m) }.onFailure { log("no shot: $it") }.getOrNull() else null
            webLink(m.node)?.let { (url, file) -> return Pick(State.Ready, url, file = file, page = if (file) pageOf() else null, shot = shot) }
            val (link, index) = linkOf(m) ?: (null to null)
            return Pick(State.Ready, link, index, shot = shot)
        } finally {
            busy--
        }
    }

    /** The photo as it shows on screen, as a JPEG, or null for a secure screen. Android 11 and up. */
    @RequiresApi(30)
    private suspend fun shoot(m: Media): File? {
        val result = aside {
            suspendCancellableCoroutine<ScreenshotResult?> { k ->
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(s: ScreenshotResult) = k.resume(s)
                    override fun onFailure(code: Int) = k.resume(null).also { log("no shot: error $code") }
                })
            }
        } ?: return null
        val app = m.node.window?.title?.toString()?.replace(BAD_NAME, "")?.trim()?.take(40)?.ifEmpty { null } ?: "LodiSave"
        val name = "$app ${STAMP.format(Date())}.jpg"
        return withContext(Dispatchers.Default) {
            val buffer = result.hardwareBuffer
            val full = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
            buffer.close()
            val r = Rect(m.bounds)
            if (full == null || !r.intersect(0, 0, full.width, full.height)) return@withContext null
            val crop = Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height())
            // A secure screen, like a bank's or a paid stream's, comes out black.
            if (black(crop)) return@withContext null.also { log("no shot: black, a secure screen") }
            File(cacheDir, "picks/${UUID.randomUUID()}").apply { mkdirs() }.resolve(name).also { f -> f.outputStream().use { crop.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
        }
    }

    /** What a web page says a photo is: its own file (true), or the page its link goes to (false).
     *  Chrome, other Chromium browsers and apps' web views tell. */
    private fun webLink(n: AccessibilityNodeInfo): Pair<String, Boolean>? {
        for (p in generateSequence(n) { it.parent }.take(4)) {
            val url = p.extras.getCharSequence(TARGET_URL)?.toString()?.takeIf { it.startsWith("http") } ?: continue
            return url to (p === n && MEDIA_ROLE.matches(n.extras.getCharSequence(CHROME_ROLE) ?: ""))
        }
        return null
    }

    /** The address in a browser's bar, which some sites want to see before they hand over a photo. */
    private fun pageOf(): String? {
        val root = appRoot() ?: return null
        val bar = URL_BARS.firstNotNullOfOrNull { root.findAccessibilityNodeInfosByViewId("${root.packageName}:id/$it").firstOrNull() }
        val text = bar?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() && ' ' !in it } ?: return null
        return if (text.startsWith("http")) text else "https://$text"
    }

    /** The post's link and the photo's place in it, read by pressing the post's Share, then Copy link. */
    private suspend fun linkOf(m: Media): Pair<String, Int?>? {
        val index = carouselIndex(m.node)
        val share = shareButton(m) ?: return null.also { log("no Share near ${m.key}") }
        val before = appWindows()
        if (!press(share)) return null.also { log("Share didn't take the press") }
        // A share sheet can take seconds the first time, while the app loads it and your contacts.
        val copyLink = waitFor(6000) { COPY_LINK.containsMatchIn(label(it).trim()) } ?: run {
            log("no Copy link after Share")
            // Close what Share opened, but don't leave the app's screen when it opened nothing.
            if (appWindows() != before) performGlobalAction(GLOBAL_ACTION_BACK)
            return null
        }
        val copied = System.currentTimeMillis()
        press(copyLink)
        delay(350)
        val link = readClip(copied)
        log("copied ${link ?: "nothing"} at carousel spot $index")
        delay(250)
        // Some apps leave their share sheet open after copying.
        if (find { COPY_LINK.containsMatchIn(label(it).trim()) } != null) performGlobalAction(GLOBAL_ACTION_BACK)
        return link?.let { it to index }
    }

    private fun appWindows() = windows.filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.map { it.id }.toSet()

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

    /** Presses a button: an accessibility click where it takes one, else a tap where it shows, as a
     *  finger would. Instagram's and Reddit's Share buttons say they can't be clicked, and take taps. */
    private suspend fun press(n: AccessibilityNodeInfo): Boolean {
        if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect().also(n::getBoundsInScreen)
        if (r.isEmpty) return false
        val tap = GestureDescription.StrokeDescription(Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }, 0, 60)
        return aside {
            suspendCancellableCoroutine<Boolean> { k ->
                val sent = dispatchGesture(GestureDescription.Builder().addStroke(tap).build(), object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription) = k.resume(true)
                    override fun onCancelled(g: GestureDescription) = k.resume(false)
                }, null)
                if (!sent) k.resume(false)
            }
        }
    }

    /** Runs `block` with LodiSave's circles, bar and floating Lodi hidden, so they stay out of a shot
     *  and a tap goes through to the app. */
    private suspend fun <T> aside(block: suspend () -> T): T {
        val ours = circles.map { it.view } + listOfNotNull(bar, BubbleService.shown)
        ours.forEach { it.visibility = View.INVISIBLE }
        try {
            delay(120) // a frame drawn without them
            return block()
        } finally {
            ours.forEach { it.visibility = View.VISIBLE }
        }
    }

    /** The first match on screen, a button over a mere label when both say the same, as Instagram's
     *  Copy link icon and the words under it do. */
    private fun find(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var label: AccessibilityNodeInfo? = null
        for (root in windows.filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.mapNotNull { it.root }) {
            val stack = ArrayDeque(listOf(root))
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                if (!n.isVisibleToUser) continue
                if (match(n)) {
                    if (n.isClickable) return n
                    label = label ?: n
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let(stack::add)
            }
        }
        return label
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

    /** The link copied since `since`. Android lets only the app in focus read the clipboard, so a
     *  one-pixel window takes the focus for a moment. No screen opens, which Xiaomi phones block. */
    private suspend fun readClip(since: Long): String? {
        val got = CompletableDeferred<String?>()
        val dot = object : View(this) {
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (hasFocus) got.complete(copiedLink(context, since))
            }
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(dot, params)
        return try {
            withTimeoutOrNull(2000) { got.await() }
        } finally {
            runCatching { wm.removeView(dot) }
        }
    }

    // ----- Saving -----

    private fun save() {
        val ready = picks.values.filter { it.state == State.Ready }
        if (ready.isEmpty()) return
        val works = buildList {
            // Photos ticked in one post save together, the ticked ones only.
            for ((link, ps) in ready.filter { it.link != null && !it.file }.groupBy { it.link!! }) {
                val spots = ps.mapNotNull { it.index }
                add(SaveWorker.enqueue(this@PickerService, link, if (spots.size == ps.size) spots.distinct().sorted().joinToString(",") else null, shots = ps.mapNotNull { it.shot }))
            }
            for (p in ready.filter { it.file }) add(SaveWorker.enqueue(this@PickerService, p.link!!, file = true, page = p.page, shots = listOfNotNull(p.shot)))
            // No link at all: the shots of what was on screen.
            ready.filter { it.link == null }.mapNotNull { it.shot }.takeIf { it.isNotEmpty() }?.let { add(SaveWorker.enqueue(this@PickerService, "", shots = it)) }
        }
        stop()
        // Xiaomi phones can keep the card from opening over another app. The saves run either way.
        Toast.makeText(this, copy.savingN(ready.size), Toast.LENGTH_SHORT).show()
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

        // "android.widget.Image" is a photo on a web page in Chrome and apps' web views.
        private val MEDIA_CLASS = Regex("""(Image|ImageView|VideoView|TextureView|SurfaceView|PlayerView)$""")
        private val MEDIA_WORDS = Regex("""\b(photo|image|picture|video|reel|gif|larawan|litrato|bidyo)\b""", RegexOption.IGNORE_CASE)
        private val MEDIA_ID = Regex("""image|photo|picture|media|video|player|thumbnail|gif""", RegexOption.IGNORE_CASE)
        // What Chromium's web views (Chrome 98 and up, and apps' in-app browsers) put in a node's
        // extras: its HTML role, and an image's own address (its srcset pick) or a link's.
        private const val CHROME_ROLE = "AccessibilityNodeInfo.chromeRole"
        private const val TARGET_URL = "AccessibilityNodeInfo.targetUrl"
        private val MEDIA_ROLE = Regex("""image|video""")
        // The address bars of Chrome and the Chromium browsers built on it, Samsung Internet, Firefox and Opera.
        private val URL_BARS = listOf("url_bar", "location_bar_edit_text", "mozac_browser_toolbar_url_view", "url_field")
        // Not a bare "Send", which in a chat app sends what you typed.
        private val SHARE = Regex("""^(share|send post|send this|i-share|ibahagi|ipadala ang post|compartir|partager|teilen)\b""", RegexOption.IGNORE_CASE)
        private val COPY_LINK = Regex("""^(copy(\s+(link|url))?|kopyahin(\s+ang\s+link)?|i-copy\s+ang\s+link|copiar\s+(enlace|link)|copier\s+le\s+lien|link\s+kopieren)\b""", RegexOption.IGNORE_CASE)
        private val COUNTER = Regex("""(\d+)\s*(?:/|of|sa)\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val BAD_NAME = Regex("""[\\/:*?"<>|\n]""")
        private val STAMP = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US)

        /** Black all over, as a secure screen's shot is. */
        private fun black(b: Bitmap): Boolean {
            val step = maxOf(1, minOf(b.width, b.height) / 12)
            for (y in 0 until b.height step step) for (x in 0 until b.width step step) if (b.getPixel(x, y) and 0xFFFFFF != 0) return false
            return true
        }

        /** Whether the user turned LodiSave's picker on in Android's accessibility settings. */
        fun enabled(ctx: Context): Boolean {
            val on = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val me = ComponentName(ctx, PickerService::class.java)
            return on.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
