package io.github.jherobred.lodisave

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A text field in LodiSave's look: a password, a 6-digit code or plain text. */
@Composable
fun Field(value: String, onChange: (String) -> Unit, hint: String, kind: String = "text", focus: Boolean = false) {
    val t = LocalTokens.current
    val asker = remember { FocusRequester() }
    if (focus) LaunchedEffect(Unit) { asker.requestFocus() }
    val code = kind == "code"
    BasicTextField(
        value, { onChange(if (code) it.filter { c -> c.isDigit() }.take(6) else it) },
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(CircleShape).background(t.soft).border(1.dp, t.line, CircleShape)
            .padding(horizontal = 18.dp, vertical = 14.dp).focusRequester(asker),
        singleLine = true,
        textStyle = TextStyle(fontFamily = Geist, fontSize = if (code) 22.sp else 15.sp, color = t.ink, letterSpacing = if (code) 4.sp else 0.sp),
        cursorBrush = SolidColor(t.accent),
        visualTransformation = if (kind == "password") PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when (kind) { "password" -> KeyboardType.Password; "code" -> KeyboardType.NumberPassword; else -> KeyboardType.Text }),
        decorationBox = { field -> if (value.isEmpty()) Text(hint, color = t.muted, fontFamily = Geist, fontSize = 15.sp); field() },
    )
}

@Composable
private fun Note(text: String, color: Color = LocalTokens.current.muted) =
    Text(text, fontFamily = Geist, fontSize = 13.sp, color = color)

@Composable
private fun Sheet(title: String, onDismiss: () -> Unit, confirm: @Composable () -> Unit, body: @Composable () -> Unit) {
    val t = LocalTokens.current
    AlertDialog(
        onDismiss, confirm, dismissButton = { Pill(Copy.of(LocalContext.current).cancel, onDismiss, primary = false) },
        title = { Text(title, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = t.ink) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { body() } },
        containerColor = t.surface, shape = RoundedCornerShape(28.dp),
    )
}

/** Locks `items` with a new password. `onDone` hears the message to show. */
@Composable
fun LockDialog(copy: Copy, items: List<Saved>, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val covered = remember { Recovery.isSet(ctx) }
    Sheet(copy.lockTitle, { if (!busy) onDismiss() }, {
        Pill(copy.lock, {
            when {
                pass.length < 4 -> status = copy.passwordShort
                pass != again -> status = copy.passwordsDiffer
                else -> {
                    busy = true
                    scope.launch {
                        val done = withContext(Dispatchers.IO) { Locks.lock(ctx, items, pass) { i -> status = copy.locking(i, items.size) } }
                        onDone(copy.lockedN(done, items.size))
                    }
                }
            }
        }, enabled = !busy)
    }) {
        Note(copy.lockSub(items.size), t.ink)
        Note(if (covered) copy.lockWithRecovery else copy.lockNoRecovery, if (covered) t.ok else t.bad)
        Field(pass, { pass = it }, copy.password, "password", focus = true)
        Field(again, { again = it }, copy.passwordAgain, "password")
        if (status.isNotEmpty()) Note(status)
    }
}

/** Asks for a locked file's password, or a code from the authenticator. True when it opened. */
@Composable
fun UnlockDialog(copy: Copy, entry: Locks.Entry, onDismiss: () -> Unit, onOpened: (String?) -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    var byCode by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val linked = remember { Recovery.isSet(ctx) }
    fun go() {
        busy = true
        status = ""
        scope.launch {
            val answer = withContext(Dispatchers.IO) {
                if (!byCode) { if (Locks.tryPassword(entry, text)) null else copy.wrongPassword }
                else try {
                    val key = Recovery.unlock(ctx, text)
                    if (Locks.tryRecovery(entry, key)) null else copy.beforeLink
                } catch (e: Recovery.Failure) { copy.codeError(e.code) }
            }
            busy = false
            if (answer == null) onOpened(if (byCode) copy.openedByCode else null) else status = answer
        }
    }
    Sheet(if (byCode) copy.codeTitle else copy.unlockTitle, onDismiss, { Pill(copy.unlock, ::go, enabled = !busy && text.isNotEmpty()) }) {
        if (byCode) {
            if (!linked) Note(copy.notLinked, t.ink) else {
                Note(copy.codeSub, t.ink)
                Field(text, { text = it }, "123456", "code", focus = true)
            }
        } else {
            Note(copy.unlockSub, t.ink)
            Field(text, { text = it }, copy.password, "password", focus = true)
            Text(copy.forgot, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = t.accent,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { byCode = true; text = ""; status = "" }.padding(vertical = 6.dp))
        }
        if (busy) Note("…") else if (status.isNotEmpty()) Note(status, t.bad)
    }
}

/** A new password for locked files open this session. */
@Composable
fun NewPasswordDialog(copy: Copy, entries: List<Locks.Entry>, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Sheet(copy.newPassword, { if (!busy) onDismiss() }, {
        Pill(copy.done, {
            when {
                pass.length < 4 -> status = copy.passwordShort
                pass != again -> status = copy.passwordsDiffer
                else -> {
                    busy = true
                    scope.launch {
                        val done = withContext(Dispatchers.IO) { entries.count { Locks.newPassword(ctx, it, pass) } }
                        onDone(if (done > 0) copy.newPasswordSet else copy.wrongPassword)
                    }
                }
            }
        }, enabled = !busy)
    }) {
        Note(copy.newPasswordSub, LocalTokens.current.ink)
        Field(pass, { pass = it }, copy.password, "password", focus = true)
        Field(again, { again = it }, copy.passwordAgain, "password")
        if (status.isNotEmpty()) Note(status)
    }
}

/** Settings › Password recovery: link an authenticator app, or turn it off with a code. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoverySheet(copy: Copy, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(Recovery.isSet(ctx)) }
    var setup by remember { mutableStateOf<Pair<String, String>?>(null) }
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var turningOff by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = t.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(copy.recovery, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = t.ink)
            val s = setup
            when {
                on && !turningOff -> {
                    Note(copy.recoveryOn, t.ok)
                    Pill(copy.turnOff, { turningOff = true; code = ""; status = "" }, primary = false)
                }
                on -> {
                    Note(copy.turnOffSub, t.bad)
                    Field(code, { code = it }, "123456", "code", focus = true)
                    Pill(copy.turnOff, {
                        scope.launch {
                            status = withContext(Dispatchers.IO) { runCatching { Recovery.turnOff(ctx, code); "" }.getOrElse { (it as? Recovery.Failure)?.code?.let(copy::codeError) ?: "$it" } }
                            if (status.isEmpty()) { on = false; turningOff = false }
                        }
                    }, enabled = code.length == 6)
                }
                s == null -> {
                    Note(copy.recoveryOff, t.ink)
                    Pill(copy.linkAuthenticator, { setup = Recovery.begin(); code = ""; status = "" })
                }
                else -> {
                    // On a phone the authenticator usually lives on the same phone, so the link opens
                    // it instead of a QR code to scan; the key is there to type for any other app.
                    Pill(copy.addToApp, {
                        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.second))) }
                        catch (_: ActivityNotFoundException) { Toast.makeText(ctx, copy.noAuthApp, Toast.LENGTH_LONG).show() }
                    }, Modifier.fillMaxWidth())
                    Note(copy.setupKey)
                    Text(s.first, fontFamily = GeistMono, fontSize = 16.sp, color = t.ink)
                    Pill(copy.copyKey, {
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("LodiSave setup key", s.first.replace(" ", "")))
                        Toast.makeText(ctx, copy.keyCopied, Toast.LENGTH_SHORT).show()
                    }, primary = false)
                    Text(copy.typeCode, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = t.ink)
                    Field(code, { code = it }, "123456", "code")
                    Pill(copy.linkIt, {
                        busy = true
                        scope.launch {
                            status = withContext(Dispatchers.IO) { runCatching { Recovery.finish(ctx, code); "" }.getOrElse { (it as? Recovery.Failure)?.code?.let(copy::codeError) ?: "$it" } }
                            busy = false
                            if (status.isEmpty()) { on = true; setup = null; Toast.makeText(ctx, copy.linked, Toast.LENGTH_SHORT).show() }
                        }
                    }, Modifier.fillMaxWidth(), enabled = !busy && code.length == 6)
                }
            }
            if (status.isNotEmpty()) Note(status, t.bad)
        }
    }
}

/** The locked files: a lock each, or the picture itself once unlocked this session. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LockedGrid(copy: Copy, entries: List<Locks.Entry>, bottom: Dp, onTap: (Locks.Entry) -> Unit, onHold: (Locks.Entry) -> Unit) {
    val t = LocalTokens.current
    val changes = Locks.changes
    LazyVerticalGrid(
        GridCells.Fixed(3), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp + bottom),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(entries, key = { it.id }) { e ->
            val opened by produceState(Locks.openedOf(e), e.id, changes) { value = withContext(Dispatchers.IO) { Locks.open(e) } }
            Box(
                Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(t.soft).combinedClickable(onClick = { onTap(e) }, onLongClick = { onHold(e) }),
                contentAlignment = Alignment.Center,
            ) {
                val thumb = opened?.thumb
                if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else GlyphIcon(if (opened?.kind == "video") Glyph.Play else Glyph.Lock, tint = t.muted, size = 28.dp)
                Row(
                    Modifier.align(Alignment.BottomStart).padding(6.dp).clip(CircleShape).background(Color(0x9E0F0F10)).padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    GlyphIcon(Glyph.Lock, tint = Color.White, size = 12.dp)
                    Text(if (opened != null) copy.unlocked else copy.locked, color = Color.White, fontFamily = Geist, fontSize = 11.sp)
                }
            }
        }
    }
}

/** An unlocked file, full screen. It never leaves memory, except a video, which plays from
 *  LodiSave's own cache until Lock again. */
@Composable
fun LockedViewer(copy: Copy, entry: Locks.Entry, onClose: () -> Unit) {
    val ctx = LocalContext.current
    // Opening reads and decrypts the file, so it happens off the main thread; a file this session
    // can't open closes the viewer.
    val opened by produceState(Locks.openedOf(entry), entry.id) { value = withContext(Dispatchers.IO) { Locks.open(entry) }; if (value == null) onClose() }
    val o = opened
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0F0F10))) {
            if (o == null) Unit
            else if (o.kind == "video") {
                val file = remember(entry.id) { Locks.playable(ctx, entry) }
                if (file != null) AndroidView({ c -> VideoView(c).apply { setVideoURI(Uri.fromFile(file)); setOnPreparedListener { it.isLooping = true; start() } } }, Modifier.fillMaxSize().safeDrawingPadding())
            } else {
                val image = remember(entry.id) { runCatching { BitmapFactory.decodeByteArray(o.bytes, 0, o.bytes.size)?.asImageBitmap() }.getOrNull() }
                if (image != null) Image(image, o.name, Modifier.fillMaxSize().safeDrawingPadding(), contentScale = ContentScale.Fit)
            }
            Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoundButton(Glyph.Close, copy.close, onClose)
                Text(o?.name.orEmpty(), Modifier.weight(1f), fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White, maxLines = 1)
            }
        }
    }
}

/** What a hold on a locked file offers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockedMenu(copy: Copy, entry: Locks.Entry, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val t = LocalTokens.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = t.surface) {
        Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 28.dp)) {
            for ((key, glyph, label) in listOf(
                Triple("open", Glyph.Eye, copy.open), Triple("remove", Glyph.Restore, copy.removePassword),
                Triple("new", Glyph.Lock, copy.newPassword), Triple("again", Glyph.Lock, copy.lockAgain), Triple("delete", Glyph.Trash, copy.deleteForever),
            )) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPick(key) }.padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                GlyphIcon(glyph, tint = if (key == "delete") t.bad else t.ink, size = 20.dp)
                Text(label, fontFamily = Geist, fontSize = 16.sp, color = if (key == "delete") t.bad else t.ink)
            }
        }
    }
}
