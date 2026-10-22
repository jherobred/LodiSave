package io.github.jherobred.lodisave

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LodiTheme { App(clip.value, focus.intValue) } }
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

/** Where the app is. The viewer pages through `ids` from `at`, or through the trash. */
private sealed interface Screen {
    data object Home : Screen
    data object Library : Screen
    data object Intro : Screen
    data class View(val ids: List<Long>, val at: Int, val trash: Boolean) : Screen
}

/**
 * The screens and what they share: the saves, the settings that change how everything looks, and
 * the way between screens, which a back swipe can scrub through. A thumbnail grows into the viewer
 * and flies back into its tile.
 */
@Composable
private fun App(clip: String?, focus: Int) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var lang by remember { mutableStateOf(prefs.lang) }
    var moving by remember { mutableStateOf(prefs.motion) }
    var hideRecent by remember { mutableStateOf(prefs.hideRecent) }
    val copy = remember(lang) { Copy(lang == "tl") }
    val motion = moving && !Gaming.on
    val infos by remember { WorkManager.getInstance(ctx).getWorkInfosByTagFlow(SaveWorker.TAG) }.collectAsState(emptyList())
    val finished = infos.count { it.state == WorkInfo.State.SUCCEEDED }
    var recent by remember { mutableStateOf(emptyList<Saved>()) }
    var media by remember { mutableStateOf(emptyList<Saved>()) }
    var bin by remember { mutableStateOf(emptyList<Saved>()) }
    // Again on each return and after each change, for saves made from the share card or the widget meanwhile.
    LaunchedEffect(finished, focus, Library.changes) {
        val (r, m, b) = withContext(Dispatchers.IO) { Triple(Saver.recent(ctx), Saver.media(ctx), Saver.media(ctx, trash = true)) }
        recent = r; media = m; bin = b
    }
    val all = remember(media, bin) { (media + bin).associateBy { it.id } }
    val albums = remember(media, Library.changes) { Library.albums(ctx, media) }
    var seen by remember { mutableStateOf<Long?>(null) }
    var settings by remember { mutableStateOf(false) }
    var accounts by remember { mutableStateOf(false) }

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
    fun introDone() {
        prefs.intro = true
        if (stack.size > 1) return back()
        stack[0] = Screen.Home
        scope.launch { nav.animateTo(Screen.Home) }
    }
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
    CompositionLocalProvider(LocalMotion provides motion) {
        SharedTransitionLayout(Modifier.fillMaxSize().background(LocalTokens.current.ground)) {
            CompositionLocalProvider(LocalShared provides this) {
                rememberTransition(nav, "screens").AnimatedContent(
                    transitionSpec = { change(initialState, targetState, motion) },
                    contentKey = { it.javaClass },
                ) { screen ->
                    CompositionLocalProvider(LocalScreen provides this) {
                        when (screen) {
                            Screen.Home -> saved.SaveableStateProvider("home") {
                                Home(clip, focus, copy, infos, recent, hideRecent, { hideRecent = it; prefs.hideRecent = it },
                                    onSettings = { settings = true }, onLibrary = { seen = null; go(Screen.Library) }, onOpen = { at -> go(Screen.View(recent.map { it.id }, at, false)) })
                            }
                            Screen.Library -> saved.SaveableStateProvider("library") {
                                Library(copy, media, bin, seen, onOpen = { ids, at, trash -> go(Screen.View(ids, at, trash)) }, onBack = ::back)
                            }
                            Screen.Intro -> Intro(copy, focus, ::introDone)
                            is Screen.View -> Viewer(copy, screen.ids, screen.at, screen.trash, all, albums, onSeen = { seen = it }, onBack = ::back)
                        }
                    }
                }
            }
        }

        if (settings) SettingsSheet(
            copy, focus, lang, { lang = it; prefs.lang = it; SaveWidget.refresh(ctx) }, moving, { moving = it; prefs.motion = it },
            hideRecent, { hideRecent = it; prefs.hideRecent = it },
            onAccounts = { settings = false; accounts = true }, onIntro = { settings = false; go(Screen.Intro) }, onDismiss = { settings = false },
        )
        if (accounts) AccountsSheet(copy) { accounts = false }
    }
}

/** How one screen gives way to the next: the viewer fades over its grid while the thumbnail grows,
 *  the Library slides in from the side, the intro settles in. */
private fun AnimatedContentTransitionScope<Screen>.change(from: Screen, to: Screen, motion: Boolean): ContentTransform {
    if (!motion) return EnterTransition.None togetherWith ExitTransition.None
    if (from is Screen.View || to is Screen.View) return fadeIn(tween(280)) togetherWith fadeOut(tween(280))
    if (from is Screen.Intro || to is Screen.Intro) return (fadeIn(tween(360)) + scaleIn(spring(0.9f, 380f), 0.94f)) togetherWith fadeOut(tween(200))
    val forward = to is Screen.Library
    val slide = spring(0.92f, 380f, IntOffset(1, 1))
    return (slideInHorizontally(slide) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(240))) togetherWith
        (slideOutHorizontally(slide) { if (forward) -it / 5 else it / 5 } + fadeOut(tween(200))) using SizeTransform(clip = false)
}

@Composable
private fun Home(
    clip: String?, focus: Int, copy: Copy, infos: List<WorkInfo>, recent: List<Saved>, hideRecent: Boolean, onHideRecent: (Boolean) -> Unit,
    onSettings: () -> Unit, onLibrary: () -> Unit, onOpen: (Int) -> Unit,
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

    Column(Modifier.fillMaxSize().background(t.ground).safeDrawingPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                    else -> LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(recent, key = { _, it -> it.id }) { i, item ->
                            RecentItem(item, copy, if (entered) -1 else i + 1, if (motion) Modifier.animateItem() else Modifier.animateItem(null, null, null)) { onOpen(i) }
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
    hideRecent: Boolean, onHideRecent: (Boolean) -> Unit, onAccounts: () -> Unit, onIntro: () -> Unit, onDismiss: () -> Unit,
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

/** A recent save: tap to open it in the viewer, long-press to keep it off home. */
@Composable
private fun RecentItem(item: Saved, copy: Copy, order: Int, modifier: Modifier, onOpen: () -> Unit) {
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
            Hero("m${item.id}", Modifier.fillMaxWidth().aspectRatio(1.2f), RoundedCornerShape(18.dp)) {
                Box(Modifier.fillMaxSize().background(t.soft), contentAlignment = Alignment.Center) {
                    if (thumb != null) Image(thumb, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else if (item.kind == "audio") GlyphIcon(Glyph.Music, tint = t.muted, size = 30.dp)
                }
            }
            Text(item.name, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val age = System.currentTimeMillis() / 1000 - item.added
            Text("${copy.ago(age)} · ${sizeText(item.bytes)}", fontFamily = GeistMono, fontSize = 11.sp, color = t.muted)
            DropdownMenu(menu, { menu = false }, containerColor = t.surface) {
                DropdownMenuItem({ Text(copy.hideFromHome, fontFamily = Geist, color = t.ink) }, { menu = false; Library.hide(ctx, listOf(item), true) },
                    leadingIcon = { GlyphIcon(Glyph.EyeOff, size = 20.dp) })
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
