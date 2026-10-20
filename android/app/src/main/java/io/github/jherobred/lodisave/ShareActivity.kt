package io.github.jherobred.lodisave

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The floating card. Share a link, photo or video to LodiSave, or tap the widget, and Lodi floats
 * up over whatever is on screen while it saves. Leaving early is fine: the save keeps running.
 */
class ShareActivity : ComponentActivity() {
    private val job = mutableStateOf<Job>(Job.Working("reading", -1f))
    private val link = mutableStateOf("")
    private var started = false
    private var work: UUID? = null
    private var watching: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LodiTheme { Floating(job.value, link.value, ::finish) } }
        started = savedInstanceState?.getBoolean("started") == true
        // After a rotation, pick up the save that is already running.
        savedInstanceState?.getString("work")?.let { link.value = savedInstanceState.getString("link").orEmpty(); watch(UUID.fromString(it)) }
        if (!started && !intent.getBooleanExtra(EXTRA_CLIPBOARD, false)) start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("started", started)
        work?.let { outState.putString("work", it.toString()); outState.putString("link", link.value) }
    }

    // A share or widget tap while a card is still up starts that card over for the new one.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        watching?.cancel()
        work = null
        link.value = ""
        job.value = Job.Working("reading", -1f)
        started = false
        if (!intent.getBooleanExtra(EXTRA_CLIPBOARD, false)) start()
        else if (hasWindowFocus()) onWindowFocusChanged(true) // no focus change will come to read the clipboard
    }

    // The widget's link comes from the clipboard, which Android only shares once this card has focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || started || !intent.getBooleanExtra(EXTRA_CLIPBOARD, false)) return
        started = true
        val url = copiedLink(this)
        if (url == null) job.value = Job.Failed("nolink") else follow(url)
    }

    private fun start() {
        started = true
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val streams = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        when {
            streams.isNotEmpty() -> keep(streams)
            LINK.containsMatchIn(text) -> follow(LINK.find(text)!!.value)
            else -> job.value = Job.Failed("nothing")
        }
    }

    /** Copies photos and videos another app shared straight into the gallery. */
    private fun keep(streams: List<Uri>) {
        job.value = Job.Working("sharing", -1f)
        lifecycleScope.launch {
            job.value = try {
                val saved = withContext(Dispatchers.IO) { streams.map { Saver.publish(this@ShareActivity, it) } }
                Job.Done(saved, saved.map { Saver.nameOf(this@ShareActivity, it) }, saved.map { contentResolver.getType(it).orEmpty() })
            } catch (e: Exception) {
                // An app can share a file without letting LodiSave open it.
                Job.Failed((e as? Failure)?.code ?: if (e is SecurityException) "noaccess" else (e.message ?: "nothing"))
            }
        }
    }

    private fun follow(url: String) {
        link.value = url
        watch(SaveWorker.enqueue(this, url))
    }

    private fun watch(id: UUID) {
        work = id
        watching = lifecycleScope.launch {
            WorkManager.getInstance(this@ShareActivity).getWorkInfoByIdFlow(id).collect { job.value = it.toJob() }
        }
    }

    companion object {
        const val EXTRA_CLIPBOARD = "clipboard"
    }
}

@Composable
private fun Floating(job: Job, link: String, close: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val t = LocalTokens.current
    val copy = remember { Copy.of(ctx) }
    val motion = remember { Prefs(ctx).motion }
    val rise = remember { Animatable(if (motion) 1f else 0f) }
    LaunchedEffect(Unit) { rise.animateTo(0f, floaty()) }
    // A finished save tucks itself away after a moment.
    LaunchedEffect(job is Job.Done) { if (job is Job.Done) { delay(4000); close() } }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = close),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(Modifier.navigationBarsPadding().padding(16.dp).graphicsLayer { translationY = rise.value * 260f; alpha = (1f - rise.value).coerceIn(0f, 1f) }) {
            Column(
                Modifier.fillMaxWidth().padding(top = 44.dp).clip(RoundedCornerShape(30.dp)).background(t.surface)
                    .clickable(remember { MutableInteractionSource() }, null) {}.padding(top = 56.dp),
            ) {
                Card(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    Header(look(job, copy, link), motion, lodi = false)
                    if (job is Job.Working) Meter(job.percent)
                    Actions {
                        if (job is Job.Done) Pill(copy.open, { openSaved(ctx, job.uris.first(), job.mimes.firstOrNull().orEmpty()); close() }, Modifier.weight(1f))
                        Pill(copy.close, close, if (job is Job.Done) Modifier else Modifier.weight(1f), primary = false)
                    }
                }
            }
            // Lodi floats over the top edge of the card, the way the design shows him over the share sheet.
            Lodi(look(job, copy, link).pose, Modifier.size(104.dp).align(Alignment.TopCenter), motion)
        }
    }
}
