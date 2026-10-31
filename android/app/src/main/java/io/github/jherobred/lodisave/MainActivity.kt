package io.github.jherobred.lodisave

import kotlinx.coroutines.CompletableDeferred
import androidx.activity.result.IntentSenderRequest
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.unit.Dp
import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val clip = mutableStateOf<String?>(null)
    private val focus = mutableIntStateOf(0)
    /** Drive's notification opened the app: backed-up files from other apps wait to leave the phone. */
    private val free = mutableStateOf(false)
    private var answer: CompletableDeferred<Boolean>? = null
    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { answer?.complete(it.resultCode == RESULT_OK) }
    private val asker: suspend (List<android.net.Uri>) -> Boolean = { uris ->
        if (Build.VERSION.SDK_INT < 30) false
        else CompletableDeferred<Boolean>().also { answer = it; consent.launch(IntentSenderRequest.Builder(MediaStore.createWriteRequest(contentResolver, uris).intentSender).build()) }.await()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        free.value = intent?.action == FREE
        // Android's own yes or no before LodiSave changes files other apps made.
        Consent.asker = asker
        setContent { LodiTheme { App(clip.value, focus.intValue, free.value) { free.value = false } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == FREE) free.value = true
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Consent.asker === asker) Consent.asker = null
    }

    companion object {
        const val FREE = "io.github.jherobred.lodisave.FREE"
    }

    // Back from Android's "display over other apps" page, or any return: float Lodi if it's on.
    override fun onResume() {
        super.onResume()
        BubbleService.sync(this)
    }

    // Android shares the clipboard only with the app in focus, so check it each time LodiSave gets focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { clip.value = copiedLink(this); focus.intValue++ }
    }
}

/** Where the app is. Home, the Library and the Gallery are tabs. The viewer pages through `ids` from
 *  `at`, or through the trash, and its thumbnails fly back to the grid it opened `from`. */
private sealed interface Screen {
    data object Home : Screen
    data object Library : Screen
    data object Gallery : Screen
    data object Intro : Screen
    data object Profile : Screen
    data class View(val ids: List<Long>, val at: Int, val trash: Boolean, val from: String) : Screen
}

private val TABS = listOf(Screen.Home, Screen.Library, Screen.Gallery)

/**
 * The screens and what they share: the saves and the phone's gallery, the settings that change how
 * everything looks, and the way between screens, which a back swipe can scrub through. A thumbnail
 * grows into the viewer and flies back into its tile. `free` asks to remove files already in Drive.
 */
@Composable
private fun App(clip: String?, focus: Int, free: Boolean, onFreeDone: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var lang by remember { mutableStateOf(prefs.lang) }
    var moving by remember { mutableStateOf(prefs.motion) }
    var hideRecent by remember { mutableStateOf(prefs.hideRecent) }
    var loops by remember { mutableStateOf(prefs.loops) }
    var layout by remember { mutableStateOf(prefs.layout) }
    var blur by remember { mutableStateOf(prefs.blur) }
    val copy = remember(lang) { Copy(lang == "tl") }
    val motion = moving && !Gaming.on
    val infos by remember { WorkManager.getInstance(ctx).getWorkInfosByTagFlow(SaveWorker.TAG) }.collectAsState(emptyList())
    val finished = infos.count { it.state == WorkInfo.State.SUCCEEDED }
    var recent by remember { mutableStateOf(emptyList<Saved>()) }
    var media by remember { mutableStateOf(emptyList<Saved>()) }
    var bin by remember { mutableStateOf(emptyList<Saved>()) }
    var access by remember { mutableStateOf(Saver.galleryAccess(ctx)) }
    var gallery by remember { mutableStateOf(emptyList<Saved>()) }
    var galleryBin by remember { mutableStateOf(emptyList<Saved>()) }
    var asked by remember { mutableIntStateOf(0) } // goes up when Android answers the gallery permission
    // Again on each return and after each change, for saves made from the share card or the widget meanwhile.
    LaunchedEffect(finished, focus, Library.changes, asked) {
        access = Saver.galleryAccess(ctx)
        val (r, m, b) = withContext(Dispatchers.IO) { Triple(Saver.recent(ctx), Saver.media(ctx), Saver.media(ctx, trash = true)) }
        recent = r; media = m; bin = b
        if (access == "none") { gallery = emptyList(); galleryBin = emptyList(); return@LaunchedEffect }
        val (g, gb) = withContext(Dispatchers.IO) { Saver.gallery(ctx) to Saver.gallery(ctx, trash = true) }
        gallery = g; galleryBin = gb
    }
    val all = remember(media, bin, gallery, galleryBin) { (gallery + galleryBin + media + bin).associateBy { it.id } }
    val albums = remember(media, Library.changes) { Library.albums(ctx, media) }
    val folders = remember(gallery, Library.changes) { Library.folders(ctx, gallery) }
    var seen by remember { mutableStateOf<Long?>(null) }
    var settings by remember { mutableStateOf(false) }
    var accounts by remember { mutableStateOf(false) }
    var drive by remember { mutableStateOf(false) }
    var sounds by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    val first = remember { if (prefs.intro) Screen.Home else Screen.Intro }
    val stack = remember { mutableStateListOf(first) }
    val nav = remember { SeekableTransitionState<Screen>(first) }
    val scope = rememberCoroutineScope()
    fun go(to: Screen) {
        stack += to
        scope.launch { nav.animateTo(to) }
    }
    fun back() {
        if (stack.size < 2) return
        stack.removeAt(stack.lastIndex)
        scope.launch { nav.animateTo(stack.last()) }
    }
    // A tab takes the place of the one showing, so back from it goes to Home, not through every tab seen.
    fun tab(to: Screen) {
        if (stack.size == 1 && stack[0] == to) return
        stack.clear()
        stack += to
        picking = false
        scope.launch { nav.animateTo(to) }
    }
    fun introDone() {
        prefs.intro = true
        if (stack.size > 1) return back()
        stack[0] = Screen.Home
        scope.launch { nav.animateTo(Screen.Home) }
    }
    BackHandler(stack.size == 1 && stack[0] in TABS && stack[0] != Screen.Home && !picking) { tab(Screen.Home) }
    val onTab = stack.size == 1 && stack[0] in TABS
    // Room for the tab bar, and the sounds over it while some play.
    val bottom = if (onTab) 84.dp + if (Sounds.tracks.isNotEmpty()) 48.dp else 0.dp else 0.dp
    // A back swipe drags the screen change along with the finger, and lets go to finish or stay.
    PredictiveBackHandler(stack.size > 1) { events ->
        val to = stack[stack.lastIndex - 1]
        try {
            events.collect {
                try { nav.seekTo(it.progress, to) } catch (_: CancellationException) {} // a newer seek took over
            }
            stack.removeAt(stack.lastIndex)
            nav.animateTo(to)
        } catch (e: CancellationException) {
            scope.launch { nav.animateTo(stack.last()) }
            throw e
        }
    }

    val saved = rememberSaveableStateHolder()
    CompositionLocalProvider(LocalMotion provides motion, LocalLoops provides loops, LocalPins provides (layout == "pins"), LocalBlur provides blur) {
        Box(Modifier.fillMaxSize()) {
            SharedTransitionLayout(Modifier.fillMaxSize().background(LocalTokens.current.ground)) {
                CompositionLocalProvider(LocalShared provides this) {
                    rememberTransition(nav, "screens").AnimatedContent(
                        transitionSpec = { change(initialState, targetState, motion) },
                        contentKey = { it.javaClass },
                    ) { screen ->
                        CompositionLocalProvider(LocalScreen provides this) {
                            when (screen) {
                                Screen.Home -> saved.SaveableStateProvider("home") {
                                    Home(clip, focus, copy, infos, recent, hideRecent, { hideRecent = it; prefs.hideRecent = it }, bottom,
                                        onSettings = { settings = true }, onLibrary = { seen = null; tab(Screen.Library) }, onProfiles = { go(Screen.Profile) },
                                        onOpen = { at -> go(Screen.View(recent.map { it.id }, at, false, "home")) })
                                }
                                Screen.Library -> saved.SaveableStateProvider("library") {
                                    Library(copy, media, bin, seen, onOpen = { ids, at, trash -> go(Screen.View(ids, at, trash, "lib")) }, bottom = bottom, onPicking = { picking = it })
                                }
                                Screen.Gallery -> saved.SaveableStateProvider("gallery") {
                                    Gallery(copy, gallery, galleryBin, seen, access, { asked++ }, onOpen = { ids, at, trash -> go(Screen.View(ids, at, trash, "gal")) }, bottom, onPicking = { picking = it })
                                }
                                Screen.Intro -> Intro(copy, focus, ::introDone)
                                Screen.Profile -> Profile(copy, onBack = ::back)
                                is Screen.View -> Viewer(copy, screen.ids, screen.at, screen.trash, screen.from, all, if (screen.from == "gal") folders else albums, onSeen = { seen = it }, onBack = ::back)
                            }
                        }
                    }
                }
            }
            // The tabs, floating over the bottom, and the background sounds above them while some play.
            AnimatedVisibility(
                onTab && !picking, Modifier.align(Alignment.BottomCenter),
                enter = if (motion) slideInVertically(floaty()) { it } + fadeIn() else EnterTransition.None,
                exit = if (motion) slideOutVertically(snap()) { it } + fadeOut() else ExitTransition.None,
            ) {
                Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (Sounds.tracks.isNotEmpty()) SoundStrip(copy, { sounds = true }, Modifier.fillMaxWidth())
                    TabBar(copy, stack.firstOrNull() ?: Screen.Home, ::tab)
                }
            }
        }

        if (settings) SettingsSheet(
            copy, focus, lang, { lang = it; prefs.lang = it; SaveWidget.refresh(ctx) }, moving, { moving = it; prefs.motion = it },
            hideRecent, { hideRecent = it; prefs.hideRecent = it }, loops, { loops = it; prefs.loops = it },
            layout, { layout = it; prefs.layout = it }, blur, { blur = it; prefs.blur = it },
            onAccounts = { settings = false; accounts = true }, onDrive = { settings = false; drive = true },
            onIntro = { settings = false; go(Screen.Intro) }, onDismiss = { settings = false },
        )
        if (accounts) AccountsSheet(copy) { accounts = false }
        if (drive) DriveSheet(copy) { drive = false }
        if (sounds) SoundPicker(copy, remember(all) { all.values.filter { !it.trashed && (it.kind == "video" || it.kind == "audio") }.sortedByDescending { it.added } }) { sounds = false }
        if (free) FreeUp(copy, gallery + media, onFreeDone)
    }
}

/** The tabs: Home, LodiSave's saves, and the phone's whole gallery. */
@Composable
private fun TabBar(copy: Copy, at: Screen, onTab: (Screen) -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(t.surface).border(1.dp, t.line, RoundedCornerShape(28.dp)).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((screen, glyph, label) in listOf(Triple(Screen.Home, Glyph.Home, copy.home), Triple(Screen.Library, Glyph.Saves, copy.library), Triple(Screen.Gallery, Glyph.Photos, copy.gallery))) {
            val on = at == screen
            val fill by androidx.compose.animation.animateColorAsState(if (on) t.soft else androidx.compose.ui.graphics.Color.Transparent, snap(), label = "tab")
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(fill).clickable { onTab(screen) }.padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                GlyphIcon(glyph, tint = if (on) t.accent else t.muted, size = 22.dp)
                Text(label, fontFamily = Geist, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, fontSize = 12.sp, color = if (on) t.ink else t.muted)
            }
        }
    }
}

/** Removes the files Drive has whole, which other apps made, once Android has the user's yes. */
@Composable
private fun FreeUp(copy: Copy, phone: List<Saved>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(ctx) }
    val due = remember(phone) {
        val now = System.currentTimeMillis() / 1000
        val done = Drive.done(ctx)
        phone.filter { done[it.id]?.let { at -> prefs.freeDays >= 0 && at + prefs.freeDays * 86_400L <= now && it.bytes == Drive.size(ctx, it.id) } == true }
    }
    if (due.isEmpty()) return LaunchedEffect(Unit) { onDone() }
    Confirm(copy, copy.freeAsk(due.size), onDone, yes = copy.remove) {
        onDone()
        scope.launch {
            val failed = Library.each(due) { Library.delete(ctx, it) }
            Drive.forget(ctx, due.map { it.id })
            android.widget.Toast.makeText(ctx, copy.freed(due.size - failed), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

/** How one screen gives way to the next: the viewer fades over its grid while the thumbnail grows,
 *  tabs slide toward the side they sit on, the intro settles in. */
private fun AnimatedContentTransitionScope<Screen>.change(from: Screen, to: Screen, motion: Boolean): ContentTransform {
    if (!motion) return EnterTransition.None togetherWith ExitTransition.None
    if (from is Screen.View || to is Screen.View) return fadeIn(tween(280)) togetherWith fadeOut(tween(280))
    if (from is Screen.Intro || to is Screen.Intro) return (fadeIn(tween(360)) + scaleIn(spring(0.9f, 380f), 0.94f)) togetherWith fadeOut(tween(200))
    val forward = if (from in TABS && to in TABS) TABS.indexOf(to) > TABS.indexOf(from) else to is Screen.Profile
    val slide = spring(0.92f, 380f, IntOffset(1, 1))
    return (slideInHorizontally(slide) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(240))) togetherWith
        (slideOutHorizontally(slide) { if (forward) -it / 5 else it / 5 } + fadeOut(tween(200))) using SizeTransform(clip = false)
}

@Composable
private fun Home(
    clip: String?, focus: Int, copy: Copy, infos: List<WorkInfo>, recent: List<Saved>, hideRecent: Boolean, onHideRecent: (Boolean) -> Unit, bottom: Dp,
    onSettings: () -> Unit, onLibrary: () -> Unit, onProfiles: () -> Unit, onOpen: (Int) -> Unit,
) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val motion = LocalMotion.current
    val prefs = remember { Prefs(ctx) }
    var started by rememberSaveable { mutableStateOf(listOf<String>()) }
    var hidden by rememberSaveable { mutableStateOf(listOf<String>()) }
    var dismissed by rememberSaveable { mutableStateOf(listOf<String>()) }
    var input by rememberSaveable { mutableStateOf("") }
    var entered by rememberSaveable { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Pair<String, Job.Choose>?>(null) }
    val byId = infos.associateBy { it.id.toString() }
    // Saves started here, newest first, then ones still running from the share card or the floating Lodi.
    val tray = (started.reversed() + infos.filter { !it.state.isFinished }.map { it.id.toString() }).distinct()
        .filter { it !in hidden && it in byId }
    LaunchedEffect(Unit) { delay(700); entered = true }
    // A new save lands first: back to the top to show it, where a list would otherwise hold its place.
    val grid = rememberLazyGridState()
    LaunchedEffect(recent.firstOrNull()?.id) { if (motion && !hideRecent) grid.animateScrollToItem(0) else grid.scrollToItem(0) }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun save(url: String, items: String? = null) {
        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications) {
            prefs.askedNotifications = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        dismissed = dismissed + url
        input = ""
        started = started + SaveWorker.enqueue(ctx, url, items).toString()
    }

    Column(Modifier.fillMaxSize().background(t.ground).safeDrawingPadding().padding(start = 16.dp, end = 16.dp, bottom = bottom), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("LodiSave", fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = t.ink)
            AnimatedVisibility(Gaming.on) { Tag(copy.gaming) }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(44.dp).clip(CircleShape).background(t.soft).clickable(onClick = onSettings), contentAlignment = Alignment.Center) { SlidersIcon() }
        }

        Enter(if (entered) -1 else 0) {
            Card(Modifier.animateContentSize(if (motion) spring(0.9f, 400f) else spring(1f, 10_000f))) {
                val copied = clip?.takeIf { it !in dismissed }
                AnimatedContent(copied, transitionSpec = { if (motion) fadeIn(tween(220)) togetherWith fadeOut(tween(120)) else EnterTransition.None togetherWith ExitTransition.None }, label = "card") { link ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (link != null) {
                            Header(Look(Pose.Grab, copy.linkCopied, short(link), mono = true), motion)
                            Actions {
                                Pill(copy.save, { save(link) }, Modifier.weight(1f))
                                Pill(copy.later, { dismissed = dismissed + link }, primary = false)
                            }
                        } else {
                            Header(Look(if (tray.any { byId[it].toJob() is Job.Working }) Pose.Full else Pose.Idle, copy.idle, copy.idleSub), motion)
                            val typed = LINK.find(input)?.value
                            BasicTextField(
                                input, { input = it },
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(CircleShape).background(t.surface).border(1.dp, t.line, CircleShape).padding(horizontal = 18.dp, vertical = 14.dp),
                                singleLine = true,
                                textStyle = TextStyle(fontFamily = GeistMono, fontSize = 13.sp, color = t.ink),
                                cursorBrush = SolidColor(t.accent),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { typed?.let { save(it) } }),
                                decorationBox = { field -> if (input.isEmpty()) Text(copy.paste, color = t.muted, fontFamily = Geist, fontSize = 14.sp); field() },
                            )
                            Actions { Pill(copy.save, { typed?.let { save(it) } }, Modifier.weight(1f), enabled = typed != null) }
                            // Or by username: a profile's stories, highlights and posts to pick from.
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onProfiles).padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                GlyphIcon(Glyph.Person, tint = t.accent)
                                Column(Modifier.weight(1f)) {
                                    Text(copy.profiles, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = t.ink)
                                    Text(copy.profilesSub, fontFamily = Geist, fontSize = 12.sp, color = t.muted)
                                }
                                Text("›", fontFamily = Geist, fontSize = 20.sp, color = t.muted)
                            }
                        }
                    }
                }
            }
        }

        // Saves side by side, each with its own bar, like the PC picker's saving tray.
        if (tray.isNotEmpty()) Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(copy.tray, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = t.muted)
            for (id in tray.take(4)) key(id) {
                val info = byId.getValue(id)
                val job = info.toJob()
                TrayRow(job, info.link, copy) {
                    when (job) {
                        is Job.Choose -> Pill(copy.pick, { picking = id to job })
                        is Job.Done -> {
                            if (job.uris.isNotEmpty()) Pill(copy.open, { openSaved(ctx, job.uris.first(), job.mimes.firstOrNull().orEmpty()) })
                            Pill("✕", { hidden = hidden + id }, primary = false)
                        }
                        is Job.Failed -> {
                            // Signed in, the same link can open what your account sees: it saves again.
                            if (job.code == "login") SignInPill(copy, info.link) { hidden = hidden + id; save(info.link) }
                            if (job.code != "login" && job.code != "gone") Pill(copy.again, { hidden = hidden + id; save(info.link) }, primary = false)
                            Pill("✕", { hidden = hidden + id }, primary = false)
                        }
                        else -> {}
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(copy.recent, Modifier.weight(1f), fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = t.muted)
            RoundButton(if (hideRecent) Glyph.EyeOff else Glyph.Eye, if (hideRecent) copy.showRecent else copy.hideRecent, { onHideRecent(!hideRecent) }, size = 36.dp)
            Text(copy.seeAll, color = t.accent, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onLibrary).padding(horizontal = 12.dp, vertical = 8.dp))
        }
        Box(Modifier.weight(1f)) {
            val veiled = rememberVeil()
            AnimatedContent(hideRecent, transitionSpec = { if (motion) fadeIn() togetherWith fadeOut() else EnterTransition.None togetherWith ExitTransition.None }, label = "recent") { hide ->
                when {
                    // A calm line in place of the thumbnails, to bring them back.
                    hide -> Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(t.soft).clickable { onHideRecent(false) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        GlyphIcon(Glyph.EyeOff, tint = t.muted)
                        Column(Modifier.weight(1f)) {
                            Text(copy.recentHidden, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = t.ink)
                            Text(copy.tapToShow, fontFamily = Geist, fontSize = 12.sp, color = t.muted)
                        }
                    }
                    recent.isEmpty() -> Text(copy.none, color = t.muted, fontFamily = Geist, fontSize = 13.sp)
                    else -> LazyVerticalGrid(GridCells.Fixed(3), state = grid, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(recent, key = { _, it -> it.id }) { i, item ->
                            RecentItem(item, copy, veiled(item), if (entered) -1 else i + 1, if (motion) Modifier.animateItem() else Modifier.animateItem(null, null, null)) { onOpen(i) }
                        }
                    }
                }
            }
        }

        WidgetRow(copy, motion)
        Spacer(Modifier.height(4.dp))
    }

    picking?.let { (id, choose) ->
        ModalBottomSheet(onDismissRequest = { picking = null }, containerColor = t.surface) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Header(look(choose, copy, choose.url), motion)
                Picker(choose, copy) { items -> hidden = hidden + id; picking = null; save(choose.url, items) }
            }
        }
    }
}

@Composable
private fun SettingsSheet(
    copy: Copy, focus: Int, lang: String, onLang: (String) -> Unit, motion: Boolean, onMotion: (Boolean) -> Unit,
    hideRecent: Boolean, onHideRecent: (Boolean) -> Unit, loops: Boolean, onLoops: (Boolean) -> Unit,
    layout: String, onLayout: (String) -> Unit, blur: String, onBlur: (String) -> Unit,
    onAccounts: () -> Unit, onDrive: () -> Unit, onIntro: () -> Unit, onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val prefs = remember { Prefs(ctx) }
    var bubble by remember { mutableStateOf(prefs.bubble) }
    var gaming by remember { mutableStateOf(prefs.gaming) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = t.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(copy.settings, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = t.ink)
            SettingRow(copy.language) { Segmented(listOf("en" to "English", "tl" to "Taglish"), lang, onLang) }
            SettingRow(copy.motion) { Segmented(listOf("on" to copy.on, "off" to copy.off), if (motion) "on" else "off") { onMotion(it == "on") } }
            SettingRow(copy.recentOnHome) { Segmented(listOf("show" to copy.show, "hide" to copy.hide), if (hideRecent) "hide" else "show") { onHideRecent(it == "hide") } }
            SettingRow(copy.loops) { Segmented(listOf("on" to copy.on, "off" to copy.off), if (loops) "on" else "off") { onLoops(it == "on") } }
            Text(copy.loopsHint, fontFamily = Geist, fontSize = 13.sp, color = t.muted)
            SettingRow(copy.layout) { Segmented(listOf("grid" to copy.layoutGrid, "pins" to copy.layoutPins), layout, onLayout) }
            SettingRow(copy.blur) { Segmented(listOf("off" to copy.off, "all" to copy.blurAll, "pick" to copy.blurPicked), blur, onBlur) }
            Text(copy.blurHint, fontFamily = Geist, fontSize = 13.sp, color = t.muted)
            SettingRow(copy.drive) { Pill(copy.open, onDrive, primary = false) }
            Text(copy.driveHint, fontFamily = Geist, fontSize = 13.sp, color = t.muted)
            SettingRow(copy.bubble) {
                Segmented(listOf("on" to copy.on, "off" to copy.off), if (bubble) "on" else "off") {
                    bubble = it == "on"
                    prefs.bubble = bubble
                    // Floating over other apps needs Android's permission first. LodiSave starts Lodi on return.
                    if (bubble && !Settings.canDrawOverlays(ctx)) ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${ctx.packageName}".toUri()))
                    else BubbleService.sync(ctx)
                }
            }
            Text(copy.bubbleHint, fontFamily = Geist, fontSize = 13.sp, color = t.muted)
            // Checked again on each return, after the user switches it in Android's settings.
            val picker = remember(focus) { PickerService.enabled(ctx) }
            SettingRow(copy.picker) {
                Segmented(listOf("on" to copy.on, "off" to copy.off), if (picker) "on" else "off") {
                    // Lodi is the picker's switch, so he floats too.
                    if (it == "on" && !bubble) { bubble = true; prefs.bubble = true }
                    // Only the user can turn an accessibility service on or off, in Android's settings.
                    if (it == "on" && !Settings.canDrawOverlays(ctx)) ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${ctx.packageName}".toUri()))
                    else ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            Text("${copy.pickerHint} ${copy.pickerRestricted}", fontFamily = Geist, fontSize = 13.sp, color = t.muted)
            Link(copy.appInfo) { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${ctx.packageName}".toUri())) }
            // Xiaomi phones keep LodiSave's card from opening over other apps until this is allowed.
            if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) Link(copy.xiaomi) {
                runCatching { ctx.startActivity(Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", ctx.packageName)) }
                    .onFailure { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${ctx.packageName}".toUri())) }
            }
            SettingRow(copy.gaming) {
                Segmented(listOf("auto" to copy.auto, "off" to copy.off), gaming) { gaming = it; prefs.gaming = it }
            }
            // Only the picker sees which app is in front, so without it there's no gaming mode.
            Text(if (picker) copy.gamingHint else copy.gamingNeedsPicker, fontFamily = Geist, fontSize = 13.sp, color = t.muted)
            SettingRow(copy.accounts) { Pill(copy.open, onAccounts, primary = false) }
            Text(copy.accountsHint, fontFamily = Geist, fontSize = 13.sp, color = t.muted)
            Link(copy.showIntro, onIntro)
            Text("LodiSave ${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName} · github.com/jherobred/LodiSave", fontFamily = GeistMono, fontSize = 12.sp, color = t.muted,
                modifier = Modifier.clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/jherobred/LodiSave".toUri())) } })
        }
    }
}

@Composable
private fun SettingRow(label: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = LocalTokens.current.ink)
        control()
    }
}

@Composable
private fun Link(text: String, onClick: () -> Unit) =
    Text(text, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = LocalTokens.current.accent,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(vertical = 4.dp))

/** A recent save: tap to open it in the viewer, long-press to keep it off home, or blur or unblur it
 *  while blur is on. `veiled`, it shows blurred. */
@Composable
private fun RecentItem(item: Saved, copy: Copy, veiled: Boolean, order: Int, modifier: Modifier, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val thumb = rememberThumb(item)
    var menu by remember { mutableStateOf(false) }
    val press = remember { MutableInteractionSource() }
    val scale = pressScale(press)
    Enter(order, modifier) {
        Column(
            Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.combinedClickable(press, null, onLongClick = { menu = true }, onClick = onOpen),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Hero("home${item.id}", Modifier.fillMaxWidth().aspectRatio(1.2f), RoundedCornerShape(18.dp)) {
                Box(Modifier.fillMaxSize().background(t.soft), contentAlignment = Alignment.Center) {
                    if (thumb != null) Image(thumb, item.name, if (veiled) Modifier.fillMaxSize().blur(24.dp, BlurredEdgeTreatment.Rectangle) else Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else if (item.kind == "audio") GlyphIcon(Glyph.Music, tint = t.muted, size = 30.dp)
                    // Android 11 and older can't blur, so the picture is covered instead.
                    if (veiled) Box(Modifier.fillMaxSize().background(if (Build.VERSION.SDK_INT < 31) t.soft else Color.Transparent), contentAlignment = Alignment.Center) {
                        GlyphIcon(Glyph.EyeOff, Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).padding(7.dp), Color.White, 20.dp)
                    }
                }
            }
            Text(item.name, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val age = System.currentTimeMillis() / 1000 - item.added
            Text("${copy.ago(age)} · ${sizeText(item.bytes)}", fontFamily = GeistMono, fontSize = 11.sp, color = t.muted)
            DropdownMenu(menu, { menu = false }, containerColor = t.surface) {
                DropdownMenuItem({ Text(copy.hideFromHome, fontFamily = Geist, color = t.ink) }, { menu = false; Library.hide(ctx, listOf(item), true) },
                    leadingIcon = { GlyphIcon(Glyph.EyeOff, size = 20.dp) })
                if (LocalBlur.current != "off") DropdownMenuItem({ Text(if (veiled) copy.unblur else copy.blurIt, fontFamily = Geist, color = t.ink) },
                    { menu = false; Library.veil(ctx, listOf(item), !veiled) }, leadingIcon = { GlyphIcon(if (veiled) Glyph.Eye else Glyph.EyeOff, size = 20.dp) })
            }
        }
    }
}

/** Puts the widget on the home screen in one tap, where the launcher supports it. */
@Composable
private fun WidgetRow(copy: Copy, motion: Boolean) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val manager = remember { ctx.getSystemService(AppWidgetManager::class.java) }
    if (manager?.isRequestPinAppWidgetSupported != true) return
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(t.soft).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Lodi(Pose.Idle, Modifier.size(40.dp), motion)
        Column(Modifier.weight(1f)) {
            Text(copy.widget, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = t.ink)
            Text(copy.widgetHint, fontFamily = Geist, fontSize = 12.sp, color = t.muted)
        }
        Pill(copy.widgetAdd, { manager.requestPinAppWidget(ComponentName(ctx, SaveWidget::class.java), null, null) })
    }
}
