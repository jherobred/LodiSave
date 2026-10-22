package io.github.jherobred.lodisave

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.launch

private const val PAGES = 5

/**
 * The intro, on first run and again from Settings: Lodi says hi, the ways to save, picking in other
 * apps and its two switches, notifications, and done. Each switch shows whether it's on right now,
 * checked again on each return from Android's settings (`focus`), and every step can be skipped.
 */
@Composable
fun Intro(copy: Copy, focus: Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(ctx) }
    val pager = rememberPagerState { PAGES }
    var asked by remember { mutableIntStateOf(0) }
    val bubble = remember(focus, asked) { prefs.bubble && Settings.canDrawOverlays(ctx) }
    val picker = remember(focus, asked) { PickerService.enabled(ctx) }
    val notify = remember(focus, asked) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { asked++ }
    fun next() {
        scope.launch { if (pager.currentPage == PAGES - 1) onDone() else pager.animateScrollToPage(pager.currentPage + 1) }
    }
    fun open(intent: Intent) = runCatching { ctx.startActivity(intent) }

    Column(Modifier.fillMaxSize().background(t.ground).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Spacer(Modifier.weight(1f))
            Text(copy.introClose, color = t.muted, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onDone).padding(horizontal = 12.dp, vertical = 10.dp))
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (page) {
                    0 -> {
                        Spacer(Modifier.height(24.dp))
                        Enter(0) { Lodi(Pose.Yay, Modifier.size(180.dp), LocalMotion.current) }
                        Enter(1) { Title(copy.welcome) }
                        Enter(2) { Sub(copy.welcomeSub) }
                    }
                    1 -> {
                        Enter(0) { Lodi(Pose.Grab, Modifier.size(120.dp), LocalMotion.current) }
                        Enter(1) { Title(copy.ways) }
                        Enter(2) { Way(Glyph.Share, copy.wayShare, copy.wayShareSub) }
                        Enter(3) { Way(Glyph.Open, copy.wayCopy, copy.wayCopySub) }
                        Enter(4) { Way(Glyph.Plus, copy.wayLodi, copy.wayLodiSub) }
                    }
                    2 -> {
                        Enter(0) { Lodi(Pose.Full, Modifier.size(120.dp), LocalMotion.current) }
                        Enter(1) { Title(copy.picker) }
                        Enter(2) { Sub(copy.pickIntro) }
                        Enter(3) {
                            Step(copy, "1", copy.bubble, copy.stepBubbleSub, bubble) {
                                prefs.bubble = true
                                // Floating over other apps needs Android's permission first. LodiSave starts Lodi on return.
                                if (!Settings.canDrawOverlays(ctx)) open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${ctx.packageName}".toUri()))
                                else BubbleService.sync(ctx)
                                asked++
                            }
                        }
                        Enter(4) { Step(copy, "2", copy.stepPicker, copy.stepPickerSub, picker) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
                        // Android 13 and up greys out Accessibility for apps installed outside a store until this is allowed.
                        if (Build.VERSION.SDK_INT >= 33 && !picker) Enter(5) {
                            Tip(copy.pickerRestricted, copy.appInfo) { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${ctx.packageName}".toUri())) }
                        }
                        if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) Enter(6) {
                            Tip(copy.xiaomi, copy.turnOn) {
                                runCatching { ctx.startActivity(Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", ctx.packageName)) }
                                    .onFailure { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${ctx.packageName}".toUri())) }
                            }
                        }
                    }
                    3 -> {
                        Spacer(Modifier.height(24.dp))
                        Enter(0) { Lodi(Pose.Idle, Modifier.size(140.dp), LocalMotion.current) }
                        Enter(1) { Title(copy.notifyTitle) }
                        Enter(2) { Sub(copy.notifySub) }
                        Enter(3) {
                            Step(copy, "", copy.notifications, "", notify, copy.allow) {
                                // Asked once already and still off: only Android's settings can turn it on now.
                                if (prefs.askedNotifications) open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                                else if (Build.VERSION.SDK_INT >= 33) { prefs.askedNotifications = true; askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) }
                            }
                        }
                    }
                    else -> {
                        Spacer(Modifier.height(24.dp))
                        Enter(0) { Lodi(Pose.Yay, Modifier.size(160.dp), LocalMotion.current) }
                        Enter(1) { Title(copy.allSet) }
                        Enter(2) { Sub(copy.allSetSub) }
                        Enter(3) { Note(copy.accountsOptional) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(PAGES) { i ->
                    val on = i == pager.currentPage
                    val width by animateDpAsState(if (on) 22.dp else 8.dp, snap(), label = "dot")
                    val color by animateColorAsState(if (on) t.accent else t.line, snap(), label = "dotColor")
                    Box(Modifier.size(width, 8.dp).clip(CircleShape).background(color))
                }
            }
            val step = when (pager.currentPage) { 2 -> bubble && picker; 3 -> notify; else -> true }
            if (!step) Text(copy.introSkip, color = t.muted, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                modifier = Modifier.clip(CircleShape).clickable(onClick = ::next).padding(horizontal = 12.dp, vertical = 12.dp))
            Pill(if (pager.currentPage == PAGES - 1) copy.introStart else copy.introNext, ::next)
        }
    }
}

@Composable
private fun Title(text: String) =
    Text(text, Modifier.fillMaxWidth(), fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = LocalTokens.current.ink, textAlign = TextAlign.Center)

@Composable
private fun Sub(text: String) =
    Text(text, Modifier.fillMaxWidth(), fontFamily = Geist, fontSize = 15.sp, color = LocalTokens.current.muted, textAlign = TextAlign.Center)

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(LocalTokens.current.soft).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
)

/** One way to save, with a glyph. */
@Composable
private fun Way(glyph: Glyph, title: String, sub: String) = Panel {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(LocalTokens.current.surface), contentAlignment = Alignment.Center) { GlyphIcon(glyph, tint = LocalTokens.current.accent) }
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = LocalTokens.current.ink)
            Text(sub, fontFamily = Geist, fontSize = 13.sp, color = LocalTokens.current.muted)
        }
    }
}

/** A switch to turn on: whether it's on now, and a button that goes to where it's turned on. */
@Composable
private fun Step(copy: Copy, number: String, title: String, sub: String, on: Boolean, action: String = copy.turnOn, onTurnOn: () -> Unit) = Panel {
    val t = LocalTokens.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (number.isNotEmpty()) Box(Modifier.size(32.dp).clip(CircleShape).background(if (on) t.ok else t.surface), contentAlignment = Alignment.Center) {
            if (on) GlyphIcon(Glyph.Check, tint = t.surface, size = 18.dp)
            else Text(number, fontFamily = GeistMono, fontSize = 14.sp, color = t.ink)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = t.ink)
            Text(if (on) copy.on else copy.off, fontFamily = GeistMono, fontSize = 12.sp, color = if (on) t.ok else t.muted)
        }
        if (!on) Pill(action, onTurnOn)
    }
    if (sub.isNotEmpty() && !on) Text(sub, fontFamily = Geist, fontSize = 13.sp, color = t.muted)
}

/** A tip, with a link that goes where it says. */
@Composable
private fun Tip(text: String, link: String, onClick: () -> Unit) = Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(text, fontFamily = Geist, fontSize = 13.sp, color = LocalTokens.current.muted)
    Text(link, color = LocalTokens.current.accent, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(vertical = 4.dp))
}

@Composable
private fun Note(text: String) = Panel {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GlyphIcon(Glyph.Lock, tint = LocalTokens.current.ok, size = 18.dp)
        Text(text, Modifier.weight(1f), fontFamily = Geist, fontSize = 13.sp, color = LocalTokens.current.ink)
    }
}
