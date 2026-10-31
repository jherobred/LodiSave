package io.github.jherobred.lodisave

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Sounds behind whatever shows: the audio of other videos and audio files, as many as you add, each
 * on a loop until you stop it. They play on across the app and with the screen off, kept alive by
 * [SoundService], whose notification pauses or stops them. A player with no picture to draw on
 * decodes only the sound.
 */
object Sounds {
    class Track(val item: Saved, val player: MediaPlayer) {
        var playing by mutableStateOf(true)
        var ready = false
    }

    val tracks = mutableStateListOf<Track>()

    fun add(ctx: Context, item: Saved) {
        if (tracks.any { it.item.id == item.id }) return
        val app = ctx.applicationContext
        val player = MediaPlayer()
        val track = Track(item, player)
        player.runCatching {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
            isLooping = true
            setOnPreparedListener { track.ready = true; if (track.playing) it.start() }
            setOnErrorListener { _, _, _ -> stop(app, track); true }
            setDataSource(app, item.uri)
            prepareAsync()
        }.onFailure { player.release(); return }
        tracks += track
        SoundService.sync(app)
    }

    fun toggle(ctx: Context, t: Track) {
        t.playing = !t.playing
        if (t.ready) if (t.playing) t.player.start() else t.player.pause()
        SoundService.sync(ctx.applicationContext)
    }

    /** Pauses all of them, or plays them all when they're all paused. */
    fun toggleAll(ctx: Context) {
        val play = tracks.none { it.playing }
        for (t in tracks) if (t.playing != play) toggle(ctx, t)
    }

    fun stop(ctx: Context, t: Track) {
        t.player.release()
        tracks.remove(t)
        SoundService.sync(ctx.applicationContext)
    }

    fun stopAll(ctx: Context) {
        for (t in tracks.toList()) t.player.release()
        tracks.clear()
        SoundService.sync(ctx.applicationContext)
    }
}

/** Keeps the background sounds playing while LodiSave is out of sight, with a notification to pause or stop them. */
class SoundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> Sounds.stopAll(this)
            TOGGLE -> Sounds.toggleAll(this)
        }
        if (Sounds.tracks.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, ID, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        return START_NOT_STICKY
    }

    private fun note(): Notification {
        val copy = Copy.of(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, copy.sounds, NotificationManager.IMPORTANCE_LOW))
        val playing = Sounds.tracks.count { it.playing }
        fun action(what: String) = PendingIntent.getService(this, what.hashCode(), Intent(this, SoundService::class.java).setAction(what), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lodi)
            .setContentTitle(copy.soundsPlaying(playing, Sounds.tracks.size))
            .setContentText(Sounds.tracks.joinToString(", ") { it.item.name })
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, if (playing > 0) copy.pauseAll else copy.playAll, action(TOGGLE))
            .addAction(0, copy.stopAll, action(STOP))
            .build()
    }

    companion object {
        private const val ID = 7
        private const val CHANNEL = "sounds"
        private const val STOP = "stop"
        private const val TOGGLE = "toggle"

        /** Starts, updates or stops the service to match the sounds. */
        fun sync(ctx: Context) {
            val service = Intent(ctx, SoundService::class.java)
            if (Sounds.tracks.isEmpty()) ctx.stopService(service) else ContextCompat.startForegroundService(ctx, service)
        }
    }
}

/** The background sounds as a row of pills: tap one to pause or play it, ✕ to stop it, + to add one. */
@Composable
fun SoundStrip(copy: Copy, onAdd: () -> Unit, modifier: Modifier = Modifier, dark: Boolean = false) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val fill = if (dark) Color.White.copy(alpha = 0.14f) else t.soft
    val ink = if (dark) Color.White else t.ink
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        item(key = "+") {
            Row(
                Modifier.heightIn(min = 36.dp).clip(CircleShape).background(fill).clickable(onClick = onAdd).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GlyphIcon(Glyph.Music, tint = ink, size = 16.dp)
                Text(if (Sounds.tracks.isEmpty()) copy.addSound else "+", color = ink, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
        items(Sounds.tracks, key = { it.item.id }) { track ->
            Row(
                Modifier.heightIn(min = 36.dp).widthIn(max = 220.dp).clip(CircleShape).background(fill).border(1.dp, if (track.playing) t.accent else Color.Transparent, CircleShape)
                    .padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f, fill = false).clip(CircleShape).clickable { Sounds.toggle(ctx, track) }.padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    GlyphIcon(if (track.playing) Glyph.Pause else Glyph.Play, tint = if (track.playing) t.accent else ink, size = 16.dp)
                    Text(track.item.name, color = ink, fontFamily = Geist, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(32.dp).clip(CircleShape).clickable { Sounds.stop(ctx, track) }, contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.Close, tint = ink, size = 14.dp)
                }
            }
        }
    }
}

/** Videos and audio to add as background sounds, three to a row. Tapping adds one or stops it. */
@Composable
fun SoundPicker(copy: Copy, choices: List<Saved>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = t.surface) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(copy.sounds, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = t.ink)
            Text(copy.soundsHint, fontFamily = Geist, fontSize = 13.sp, color = t.muted)
            if (Sounds.tracks.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                SoundStrip(copy, onAdd = {}, Modifier.weight(1f))
                Text(copy.stopAll, color = t.bad, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    modifier = Modifier.clip(CircleShape).clickable { Sounds.stopAll(ctx) }.padding(10.dp))
            }
            if (choices.isEmpty()) Text(copy.noSounds, fontFamily = Geist, fontSize = 14.sp, color = t.muted)
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxWidth().heightIn(max = 460.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(choices, key = { it.id }) { item ->
                    val on = Sounds.tracks.any { it.item.id == item.id }
                    val thumb = rememberThumb(item)
                    Box(
                        Modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(t.soft)
                            .then(if (on) Modifier.border(3.dp, t.accent, RoundedCornerShape(14.dp)) else Modifier)
                            .clickable { Sounds.tracks.firstOrNull { it.item.id == item.id }?.let { Sounds.stop(ctx, it) } ?: Sounds.add(ctx, item) },
                    ) {
                        if (thumb != null) Image(thumb, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else GlyphIcon(Glyph.Music, Modifier.align(Alignment.Center), t.muted, 30.dp)
                        Text(item.name, Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 6.dp, vertical = 3.dp),
                            color = Color.White, fontFamily = Geist, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (on) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(t.accent), contentAlignment = Alignment.Center) {
                            GlyphIcon(Glyph.Check, tint = t.accentInk, size = 16.dp)
                        }
                    }
                }
            }
        }
    }
}
