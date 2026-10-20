package io.github.jherobred.lodisave

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val clip = mutableStateOf<String?>(null)
    private val focus = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LodiTheme { Home(clip.value, focus.intValue) } }
    }

    // Android shares the clipboard only with the app in focus, so check it each time LodiSave gets focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { clip.value = copiedLink(this); focus.intValue++ }
    }
}

@Composable
private fun Home(clip: String?, focus: Int) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val prefs = remember { Prefs(ctx) }
    var lang by remember { mutableStateOf(prefs.lang) }
    var motion by remember { mutableStateOf(prefs.motion) }
    val copy = remember(lang) { Copy(lang == "tl") }
    var jobId by rememberSaveable { mutableStateOf<String?>(null) }
    var jobLink by rememberSaveable { mutableStateOf("") }
    var dismissed by rememberSaveable { mutableStateOf(listOf<String>()) }
    var input by rememberSaveable { mutableStateOf("") }
    var settings by remember { mutableStateOf(false) }
    val flow = remember(jobId) { jobId?.let { WorkManager.getInstance(ctx).getWorkInfoByIdFlow(UUID.fromString(it)) } ?: flowOf(null) }
    val info by flow.collectAsState(null)
    val job = if (jobId == null) Job.Idle else info.toJob()
    var recent by remember { mutableStateOf(emptyList<Saved>()) }
    // Again on each return, for saves made from the share card or the widget meanwhile.
    LaunchedEffect(job is Job.Done, focus) { recent = withContext(Dispatchers.IO) { Saver.recent(ctx) } }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun save(url: String) {
        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications) {
            prefs.askedNotifications = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        jobLink = url
        dismissed = dismissed + url
        input = ""
        jobId = SaveWorker.enqueue(ctx, url).toString()
    }

    Column(Modifier.fillMaxSize().background(t.ground).safeDrawingPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("LodiSave", Modifier.weight(1f), fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = t.ink)
            Box(Modifier.size(44.dp).clip(CircleShape).background(t.soft).clickable { settings = true }, contentAlignment = Alignment.Center) { SlidersIcon() }
        }

        Card {
            val showing = clip != null && clip !in dismissed
            when {
                job !is Job.Idle -> {
                    Header(look(job, copy, jobLink), motion)
                    if (job is Job.Working) Meter(job.percent)
                    Actions {
                        when (job) {
                            is Job.Done -> {
                                Pill(copy.open, { openSaved(ctx, job.uris.first(), job.mimes.firstOrNull().orEmpty()) }, Modifier.weight(1f))
                                Pill(copy.close, { jobId = null }, primary = false)
                            }
                            is Job.Failed -> {
                                if (job.code != "login" && job.code != "gone") Pill(copy.again, { save(jobLink) }, Modifier.weight(1f))
                                Pill(copy.close, { jobId = null }, primary = false)
                            }
                            else -> {}
                        }
                    }
                }
                showing -> {
                    val copied = clip
                    Header(Look(Pose.Grab, copy.linkCopied, short(copied), mono = true), motion)
                    Actions {
                        Pill(copy.save, { save(copied) }, Modifier.weight(1f))
                        Pill(copy.later, { dismissed = dismissed + copied }, primary = false)
                    }
                }
                else -> {
                    Header(Look(Pose.Idle, copy.idle, copy.idleSub), motion)
                    val link = LINK.find(input)?.value
                    BasicTextField(
                        input, { input = it },
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(CircleShape).background(t.surface).border(1.dp, t.line, CircleShape).padding(horizontal = 18.dp, vertical = 14.dp),
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = GeistMono, fontSize = 13.sp, color = t.ink),
                        cursorBrush = SolidColor(t.accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { link?.let(::save) }),
                        decorationBox = { field -> if (input.isEmpty()) Text(copy.paste, color = t.muted, fontFamily = Geist, fontSize = 14.sp); field() },
                    )
                    Actions { Pill(copy.save, { link?.let(::save) }, Modifier.weight(1f), enabled = link != null) }
                }
            }
        }

        Text(copy.recent, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = t.muted)
        Box(Modifier.weight(1f)) {
            if (recent.isEmpty()) Text(copy.none, color = t.muted, fontFamily = Geist, fontSize = 13.sp)
            else LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recent, key = { it.uri.toString() }) { item -> RecentItem(item, copy) }
            }
        }

        WidgetRow(copy, motion)
        Spacer(Modifier.height(4.dp))
    }

    if (settings) {
        ModalBottomSheet(onDismissRequest = { settings = false }, containerColor = t.surface) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(copy.settings, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = t.ink)
                SettingRow(copy.language) {
                    Segmented(listOf("en" to "English", "tl" to "Taglish"), lang) { lang = it; prefs.lang = it; SaveWidget.refresh(ctx) }
                }
                SettingRow(copy.motion) {
                    Segmented(listOf("on" to copy.on, "off" to copy.off), if (motion) "on" else "off") { motion = it == "on"; prefs.motion = motion }
                }
                Text("LodiSave ${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName} · github.com/jherobred/LodiSave", fontFamily = GeistMono, fontSize = 12.sp, color = t.muted,
                    modifier = Modifier.clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/jherobred/LodiSave".toUri())) } })
            }
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
private fun RecentItem(item: Saved, copy: Copy) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val thumb by produceState<ImageBitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(item.uri, android.util.Size(320, 320), null).asImageBitmap() }.getOrNull()
        }
    }
    Column(Modifier.clickable { openSaved(ctx, item.uri, item.mime) }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.2f).clip(RoundedCornerShape(18.dp)).background(t.soft))
        { thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
        Text(item.name, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val age = System.currentTimeMillis() / 1000 - item.added
        Text("${copy.ago(age)} · ${size(item.bytes)}", fontFamily = GeistMono, fontSize = 11.sp, color = t.muted)
    }
}

private fun size(b: Long) = if (b >= 1 shl 20) "%.1f MB".format(b / 1048576.0) else "${(b / 1024).coerceAtLeast(1)} KB"

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
