package io.github.jherobred.lodisave

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The floating card. Share a link, photo or video to LodiSave, or tap the widget or the floating
 * Lodi, and Lodi floats up over whatever is on screen while it saves. More shares while the card is
 * up save side by side, listed under the newest. Leaving early is fine: the saves keep running.
 */
class ShareActivity : ComponentActivity() {
    /** Saves this card follows, oldest first. */
    private val saves = mutableStateListOf<UUID>()
    /** A shared file being copied, or a share with nothing to save. */
    private val local = mutableStateOf<Job?>(null)
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getStringArrayList("saves")?.mapTo(saves, UUID::fromString)
        started = savedInstanceState?.getBoolean("started") == true
        setContent { LodiTheme { Floating(saves, local.value, ::pick, ::promote, ::again, ::finish) } }
        if (!started && !intent.getBooleanExtra(EXTRA_CLIPBOARD, false)) start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("started", started)
        outState.putStringArrayList("saves", ArrayList(saves.map(UUID::toString)))
    }

    // Another share or tap while the card is up joins the saves already on it.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        started = false
        if (!intent.getBooleanExtra(EXTRA_CLIPBOARD, false)) start()
        else if (hasWindowFocus()) onWindowFocusChanged(true) // no focus change will come to read the clipboard
    }

    // The widget's and the floating Lodi's link comes from the clipboard, which Android only shares
    // once this card has focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || started || !intent.getBooleanExtra(EXTRA_CLIPBOARD, false)) return
        started = true
        val url = copiedLink(this)
        if (url == null) local.value = Job.Failed("nolink") else follow(url)
    }

    private fun start() {
        started = true
        // Saves the picker started in another app, to follow here.
        intent.getStringArrayExtra(EXTRA_WORKS)?.let { ids ->
            local.value = null
            ids.mapTo(saves, UUID::fromString)
            return
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val streams = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        when {
            streams.isNotEmpty() -> keep(streams)
            LINK.containsMatchIn(text) -> follow(LINK.find(text)!!.value)
            else -> local.value = Job.Failed("nothing")
        }
    }

    /** Copies photos and videos another app shared straight into the gallery. */
    private fun keep(streams: List<Uri>) {
        local.value = Job.Working("sharing", -1f)
        lifecycleScope.launch {
            local.value = try {
                val saved = withContext(Dispatchers.IO) { streams.map { Saver.publish(this@ShareActivity, it) } }
                Job.Done(saved, saved.map { Saver.nameOf(this@ShareActivity, it) }, saved.map { contentResolver.getType(it).orEmpty() })
            } catch (e: Exception) {
                // An app can share a file without letting LodiSave open it.
                Job.Failed((e as? Failure)?.code ?: if (e is SecurityException) "noaccess" else (e.message ?: "nothing"))
            }
        }
    }

    private fun follow(url: String) {
        local.value = null
        saves += SaveWorker.enqueue(this, url)
    }

    /** Saves a link again in place of the save that tried it, as after signing in. */
    private fun again(id: UUID, url: String) {
        val at = saves.indexOf(id)
        val next = SaveWorker.enqueue(this, url)
        if (at >= 0) saves[at] = next else saves += next
    }

    /** The user picked from a post with several photos or videos: save those in its place. */
    private fun pick(id: UUID, url: String, items: String) {
        val at = saves.indexOf(id)
        val next = SaveWorker.enqueue(this, url, items)
        if (at >= 0) saves[at] = next else saves += next
    }

    /** Brings a save up to the big card, to pick from it there. */
    private fun promote(id: UUID) {
        local.value = null
        if (saves.remove(id)) saves += id
    }

    companion object {
        const val EXTRA_CLIPBOARD = "clipboard"
        const val EXTRA_WORKS = "works"
    }
}

@Composable
private fun Floating(saves: List<UUID>, local: Job?, pick: (UUID, String, String) -> Unit, promote: (UUID) -> Unit, again: (UUID, String) -> Unit, close: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val copy = remember { Copy.of(ctx) }
    val motion = remember { Prefs(ctx).motion } && !Gaming.on
    CompositionLocalProvider(LocalMotion provides motion) { Sheet(saves, local, copy, motion, pick, promote, again, close) }
}

@Composable
private fun Sheet(saves: List<UUID>, local: Job?, copy: Copy, motion: Boolean, pick: (UUID, String, String) -> Unit, promote: (UUID) -> Unit, again: (UUID, String) -> Unit, close: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val t = LocalTokens.current
    val infos by remember { WorkManager.getInstance(ctx).getWorkInfosByTagFlow(SaveWorker.TAG) }.collectAsState(emptyList())
    val byId = infos.associateBy { it.id }
    // The newest save is the big one. A shared file or a share with no link stands in when there is no save.
    val main = local ?: saves.lastOrNull()?.let { byId[it].toJob() } ?: Job.Working("reading", -1f)
    val mainId = if (local == null) saves.lastOrNull() else null
    val mainLink = mainId?.let { byId[it]?.link }.orEmpty()
    val others = saves.filter { it != mainId }.reversed()
    val busy = (listOf(main) + others.map { byId[it].toJob() }).any { it is Job.Working || it is Job.Choose }

    val rise = remember { Animatable(if (motion) 1f else 0f) }
    LaunchedEffect(Unit) { rise.animateTo(0f, floaty()) }
    // Finished saves tuck the card away after a moment.
    LaunchedEffect(busy, main is Job.Done) { if (!busy && main is Job.Done) { delay(4000); close() } }
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
                // Over a game, a note that LodiSave is keeping quiet.
                if (Gaming.on) Tag(copy.gaming, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
                Card(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp).animateContentSize(spring(0.9f, 400f))) {
                    // A change of state swaps the card's words and buttons with a quick fade; progress alone just moves the bar.
                    AnimatedContent(
                        main, contentKey = { it.javaClass },
                        transitionSpec = { if (motion) fadeIn(tween(220)) togetherWith fadeOut(tween(120)) else EnterTransition.None togetherWith ExitTransition.None },
                        label = "card",
                    ) { job ->
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Header(look(job, copy, mainLink), motion, lodi = false)
                            when (job) {
                                is Job.Working -> Meter(job.percent)
                                is Job.Choose -> Picker(job, copy) { items -> mainId?.let { pick(it, job.url, items) } }
                                else -> {}
                            }
                            Actions {
                                if (job is Job.Done && job.uris.isNotEmpty()) Pill(copy.open, { openSaved(ctx, job.uris.first(), job.mimes.firstOrNull().orEmpty()); close() }, Modifier.weight(1f))
                                if (job is Job.Failed && job.code == "login" && mainId != null) SignInPill(copy, mainLink) { again(mainId, mainLink) }
                                Pill(copy.close, close, if (job is Job.Done || job is Job.Failed && job.code == "login" && Site.of(mainLink) != null) Modifier else Modifier.weight(1f), primary = false)
                            }
                        }
                    }
                }
                if (others.isNotEmpty()) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val working = others.count { byId[it].toJob() is Job.Working }
                    if (working > 0) Text(copy.alsoSaving(working), fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = t.muted, modifier = Modifier.padding(start = 4.dp))
                    for (id in others.take(4)) key(id) {
                        val job = byId[id].toJob()
                        val link = byId[id]?.link.orEmpty()
                        TrayRow(job, link, copy) {
                            if (job is Job.Choose) Pill(copy.pick, { promote(id) }, primary = false)
                            if (job is Job.Failed && job.code == "login") SignInPill(copy, link) { again(id, link) }
                        }
                    }
                }
            }
            // Lodi floats over the top edge of the card, the way the design shows him over the share sheet.
            Lodi(look(main, copy, mainLink).pose, Modifier.size(104.dp).align(Alignment.TopCenter), motion)
        }
    }
}
