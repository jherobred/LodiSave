package io.github.jherobred.lodisave

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri

/**
 * The phone's own photos and videos beside LodiSave's saves: the camera's, screenshots and chat
 * apps'. Android asks once to let LodiSave see them. With limited access (Android 14 and up) it shows
 * the photos the user picked, and offers to pick more. `access` is [Saver.galleryAccess].
 */
@Composable
fun Gallery(
    copy: Copy, media: List<Saved>, bin: List<Saved>, seen: Long?, access: String, onAccess: () -> Unit,
    onOpen: (List<Long>, Int, Boolean) -> Unit, bottom: Dp, onPicking: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { asked = true; onAccess() }
    fun request() = ask.launch(Saver.galleryPermissions)
    if (access == "none") return Column(
        Modifier.fillMaxSize().background(t.ground).safeDrawingPadding().padding(start = 32.dp, end = 32.dp, bottom = bottom),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Lodi(Pose.Grab, Modifier.size(120.dp), LocalMotion.current)
        Text(copy.galleryTitle, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = t.ink, textAlign = TextAlign.Center)
        Text(copy.galleryWhy, fontFamily = Geist, fontSize = 14.sp, color = t.muted, textAlign = TextAlign.Center)
        Pill(copy.allow, ::request)
        // Android stops asking after two no's: then only its settings can allow it.
        if (asked) Text(copy.galleryDenied, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = t.accent,
            modifier = Modifier.clip(CircleShape).clickable {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${ctx.packageName}".toUri()))
            }.padding(8.dp), textAlign = TextAlign.Center)
    }
    Library(copy, media, bin, seen, onOpen, gallery = true, bottom = bottom, onPicking = onPicking) {
        if (access == "some") Row(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(t.soft)
                .clickable(onClick = ::request).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlyphIcon(Glyph.Lock, tint = t.muted, size = 18.dp)
            Text(copy.gallerySome, Modifier.weight(1f), fontFamily = Geist, fontSize = 13.sp, color = t.ink)
            Box { Text(copy.pickMore, color = t.accent, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        }
    }
}
