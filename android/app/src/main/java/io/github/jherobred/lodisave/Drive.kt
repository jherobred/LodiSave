package io.github.jherobred.lodisave

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Backups in the user's Google Drive, in a LodiSave folder there. Google's sign-in on the phone lets
 * LodiSave reach only the files it made in that Drive (drive.file), nothing else in it. [BackupWorker]
 * uploads the files picked in the Library or Gallery, and by the rule in Settings every photo and
 * video older than some days or from before a date. A file Drive confirmed whole can leave the phone
 * the set number of days after: LodiSave's own files on their own, other apps' after one tap on a
 * notification, as Android asks the user before LodiSave deletes those.
 */
object Drive {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val FILES = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    private val request: AuthorizationRequest = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Google's answer: a token for Drive, or the screen that asks the user first. */
    suspend fun authorize(ctx: Context): Result<AuthorizationResult> = suspendCancellableCoroutine { c ->
        Identity.getAuthorizationClient(ctx).authorize(request)
            .addOnSuccessListener { c.resume(Result.success(it)) }
            .addOnFailureListener { c.resume(Result.failure(it)) }
    }

    /** A token without asking, or null when the user has to say yes first, in the app. */
    suspend fun token(ctx: Context): String? = authorize(ctx).getOrNull()?.takeIf { !it.hasResolution() }?.accessToken

    // What's in Drive, in LodiSave's private files: {"folder": id, "done": {mediaId: {"file", "at", "size"}}, "queue": [mediaId]}.
    private fun file(ctx: Context) = File(ctx.filesDir, "drive.json")

    @Synchronized
    private fun read(ctx: Context) = runCatching { JSONObject(file(ctx).readText()) }.getOrElse { JSONObject() }

    @Synchronized
    private fun edit(ctx: Context, change: (JSONObject) -> Unit) {
        val json = read(ctx).also(change)
        val part = File(ctx.filesDir, "drive.part").apply { writeText(json.toString()) }
        part.renameTo(file(ctx))
    }

    /** The files in Drive, by MediaStore id: when Drive confirmed each, in seconds. */
    fun done(ctx: Context): Map<Long, Long> {
        val done = read(ctx).optJSONObject("done") ?: return emptyMap()
        return done.keys().asSequence().associate { it.toLong() to done.getJSONObject(it).optLong("at") }
    }

    /** Backs up these files now, along with any still waiting. */
    fun queue(ctx: Context, items: List<Saved>) {
        edit(ctx) { j ->
            val queue = j.optJSONArray("queue") ?: JSONArray()
            val have = List(queue.length()) { queue.getLong(it) }.toSet()
            items.map { it.id }.filter { it !in have }.forEach(queue::put)
            j.put("queue", queue)
        }
        BackupWorker.now(ctx)
    }

    fun queued(ctx: Context): Set<Long> = read(ctx).optJSONArray("queue")?.let { q -> List(q.length()) { q.getLong(it) }.toSet() }.orEmpty()

    private fun finished(ctx: Context, item: Saved, driveId: String) = edit(ctx) { j ->
        val done = j.optJSONObject("done") ?: JSONObject()
        done.put("${item.id}", JSONObject().put("file", driveId).put("at", System.currentTimeMillis() / 1000).put("size", item.bytes))
        j.put("done", done)
        val queue = j.optJSONArray("queue") ?: JSONArray()
        j.put("queue", JSONArray(List(queue.length()) { queue.getLong(it) }.filter { it != item.id }))
    }

    /** Files the phone no longer has drop out of the waiting list and the record. */
    fun forget(ctx: Context, ids: Collection<Long>) = edit(ctx) { j ->
        j.optJSONObject("done")?.let { done -> ids.forEach { done.remove("$it") } }
        j.optJSONArray("queue")?.let { q -> j.put("queue", JSONArray(List(q.length()) { q.getLong(it) }.filter { it !in ids })) }
    }

    /** The size Drive confirmed for a backed-up file, which must still match the phone's copy before it goes. */
    fun size(ctx: Context, id: Long): Long = read(ctx).optJSONObject("done")?.optJSONObject("$id")?.optLong("size", -1) ?: -1

    private fun open(url: String, token: String, method: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 20_000
        readTimeout = 120_000
        setRequestProperty("Authorization", "Bearer $token")
    }

    private fun HttpURLConnection.answer(): JSONObject {
        val ok = responseCode in 200..299
        val body = (if (ok) inputStream else errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        if (!ok) throw DriveError(responseCode, body.take(300))
        return runCatching { JSONObject(body) }.getOrElse { JSONObject() }
    }

    class DriveError(val code: Int, message: String) : Exception("Drive $code: $message")

    /** LodiSave's folder in the user's Drive, made the first time. */
    fun folder(ctx: Context, token: String, fresh: Boolean = false): String {
        if (!fresh) read(ctx).optString("folder").takeIf { it.isNotEmpty() }?.let { return it }
        val c = open("$FILES?fields=id", token, "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        c.outputStream.use { it.write(JSONObject().put("name", "LodiSave").put("mimeType", "application/vnd.google-apps.folder").toString().encodeToByteArray()) }
        val id = c.answer().getString("id")
        edit(ctx) { it.put("folder", id) }
        return id
    }

    /** Uploads a file whole, in a resumable session, and returns Drive's id for it once Drive confirms its size. */
    fun upload(ctx: Context, token: String, folder: String, item: Saved): String {
        val start = open("$UPLOAD?uploadType=resumable&fields=id,size", token, "POST")
        start.doOutput = true
        start.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        start.setRequestProperty("X-Upload-Content-Type", item.mime)
        start.setRequestProperty("X-Upload-Content-Length", "${item.bytes}")
        start.outputStream.use { it.write(JSONObject().put("name", item.name).put("parents", JSONArray().put(folder)).toString().encodeToByteArray()) }
        start.answer()
        val session = start.getHeaderField("Location") ?: throw DriveError(0, "no upload session")
        val put = open(session, token, "PUT")
        put.doOutput = true
        put.setFixedLengthStreamingMode(item.bytes)
        put.setRequestProperty("Content-Type", item.mime)
        ctx.contentResolver.openInputStream(item.uri)?.use { input -> put.outputStream.use { input.copyTo(it, 1 shl 16) } } ?: throw DriveError(0, "file gone")
        val done = put.answer()
        if (done.optString("size").toLongOrNull() != item.bytes) throw DriveError(0, "size")
        return done.getString("id").also { finished(ctx, item, it) }
    }
}

/**
 * Uploads to Drive in the background, with a notification while it does: the files waiting, then by
 * the rule in Settings the photos and videos it covers. Then frees space: a backed-up file older
 * than the days set, still as Drive got it, leaves the phone. Runs once on demand, and each day
 * while a rule or freeing space is on.
 */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun getForegroundInfo() = foreground(0, 0)

    private fun foreground(done: Int, total: Int): ForegroundInfo {
        val ctx = applicationContext
        val copy = Copy.of(ctx)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, copy.drive, NotificationManager.IMPORTANCE_LOW))
        val note = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lodi)
            .setContentTitle(copy.backingUp(done, total))
            .setOngoing(true)
            .setSilent(true)
            .setProgress(total, done, total == 0)
            .build()
        return ForegroundInfo(ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        // Google needs the user's yes in the app first: a notification takes them there.
        val token = Drive.token(ctx) ?: return@withContext Result.success().also { tell(ctx, Copy.of(ctx).driveConnect, null) }
        val phone = (if (Saver.galleryAccess(ctx) == "none") emptyList() else Saver.gallery(ctx)) + Saver.media(ctx)
        val byId = phone.associateBy { it.id }
        val done = Drive.done(ctx)
        Drive.forget(ctx, (done.keys + Drive.queued(ctx)).filter { it !in byId })
        val now = System.currentTimeMillis() / 1000
        val ruled = when (prefs.backupRule) {
            "days" -> phone.filter { it.kind != "audio" && it.added < now - prefs.backupDays * 86_400L }
            "date" -> phone.filter { it.kind != "audio" && it.added < prefs.backupBefore }
            else -> emptyList()
        }
        val todo = (Drive.queued(ctx).mapNotNull { byId[it] } + ruled).distinctBy { it.id }.filter { it.id !in done }
        if (todo.isNotEmpty()) runCatching { setForeground(foreground(0, todo.size)) }
        var folder = Drive.folder(ctx, token)
        var failed = 0
        for ((i, item) in todo.withIndex()) {
            val ok = runCatching { Drive.upload(ctx, token, folder, item) }.recoverCatching { e ->
                // The folder was deleted in Drive: a new one, and one more try.
                if (e is Drive.DriveError && e.code == 404) { folder = Drive.folder(ctx, token, fresh = true); Drive.upload(ctx, token, folder, item) } else throw e
            }.onFailure { android.util.Log.w("LodiDrive", "${item.name}: $it") }.isSuccess
            if (!ok) failed++
            runCatching { setForeground(foreground(i + 1, todo.size)) }
        }
        free(ctx, prefs, byId, now)
        // A few more tries for a dropped connection; files that still fail stay waiting for the next backup.
        if (failed > 0 && runAttemptCount < 3) Result.retry() else Result.success()
    }

    /** Backed-up files past the days set leave the phone, when they're still as Drive got them. */
    private suspend fun free(ctx: Context, prefs: Prefs, byId: Map<Long, Saved>, now: Long) {
        if (prefs.freeDays < 0) return
        val due = Drive.done(ctx).filter { (id, at) -> at + prefs.freeDays * 86_400L <= now && byId[id]?.bytes == Drive.size(ctx, id) }.keys.mapNotNull { byId[it] }
        val (own, others) = due.partition { it.path.contains("/LodiSave/") }
        // LodiSave may delete its own files; Android asks the user before it deletes another app's.
        val gone = own.filter { runCatching { Library.delete(ctx, it) }.isSuccess }
        Drive.forget(ctx, gone.map { it.id })
        if (gone.isNotEmpty()) Library.changed()
        if (others.isNotEmpty()) tell(ctx, Copy.of(ctx).freeReady(others.size), MainActivity.FREE)
    }

    private fun tell(ctx: Context, text: String, action: String?) {
        val open = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, Copy.of(ctx).drive, NotificationManager.IMPORTANCE_LOW))
        nm.notify(ID + 1, NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat_lodi).setContentTitle(Copy.of(ctx).drive)
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build())
    }

    companion object {
        private const val CHANNEL = "drive"
        private const val ID = 9
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Backs up what's waiting, after any backup running now. */
        fun now(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork("backup", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(online).build())
        }

        /** Runs each day while a rule or freeing space is on. */
        fun schedule(ctx: Context) {
            val prefs = Prefs(ctx)
            val work = WorkManager.getInstance(ctx)
            if (prefs.backupRule == "off" && prefs.freeDays < 0) work.cancelUniqueWork("backup-daily")
            else work.enqueueUniquePeriodicWork("backup-daily", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).setConstraints(online).build())
        }
    }
}

/** Settings › Google Drive backup: connect, backing up on its own by age or date, freeing space, Back up now. */
@Composable
fun DriveSheet(copy: Copy, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val prefs = remember { Prefs(ctx) }
    var connected by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { connected = Drive.token(ctx) != null }
    val connect = rememberDriveConnect { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show() }
    var rule by remember { mutableStateOf(prefs.backupRule) }
    var days by remember { mutableIntStateOf(prefs.backupDays) }
    var before by remember { mutableLongStateOf(prefs.backupBefore) }
    var free by remember { mutableIntStateOf(prefs.freeDays) }
    var dating by remember { mutableStateOf(false) }
    val count = remember { Drive.done(ctx).size }
    fun keep() {
        prefs.backupRule = rule
        prefs.backupDays = days
        prefs.backupBefore = before
        prefs.freeDays = free
        BackupWorker.schedule(ctx)
    }
    ModalBottomSheet(onDismissRequest = { keep(); onDismiss() }, containerColor = t.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(copy.drive, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = t.ink)
            Text(copy.driveHint, fontFamily = Geist, fontSize = 13.sp, color = t.muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Google Drive", fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = t.ink)
                    Text(if (connected == true) "${copy.connected} · ${copy.backedUp(count)}" else copy.notConnected, fontFamily = Geist, fontSize = 13.sp, color = if (connected == true) t.ok else t.muted)
                }
                if (connected == false) Pill(copy.connect, { connect { connected = it } })
            }
            Text(copy.autoBackup, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = t.ink)
            Segmented(listOf("off" to copy.ruleOff, "days" to copy.ruleDays, "date" to copy.ruleDate), rule) { rule = it; keep() }
            if (rule == "days") Stepper(copy.days(days), { days = (days + it).coerceIn(1, 3650); keep() })
            if (rule == "date") Pill(java.text.DateFormat.getDateInstance().format(java.util.Date(before * 1000)), { dating = true }, primary = false)
            Text(copy.freeSpace, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = t.ink)
            Segmented(listOf("off" to copy.ruleOff, "on" to copy.on), if (free < 0) "off" else "on") { free = if (it == "on") 30 else -1; keep() }
            if (free >= 0) Stepper(copy.freeAfter(free), { free = (free + it).coerceIn(0, 3650); keep() })
            Text(copy.freeHint, fontFamily = Geist, fontSize = 13.sp, color = t.muted)
            Pill(copy.backUpNow, { keep(); connect { ok -> connected = ok; if (ok) BackupWorker.now(ctx) } }, Modifier.fillMaxWidth())
        }
    }
    if (dating) {
        val state = rememberDatePickerState(initialSelectedDateMillis = before * 1000)
        DatePickerDialog(
            onDismissRequest = { dating = false },
            confirmButton = { Pill(copy.done, { state.selectedDateMillis?.let { before = it / 1000; keep() }; dating = false }) },
        ) { DatePicker(state) }
    }
}

/** A value with − and + on either side, which `onStep` moves by one, or by seven with a long press. */
@Composable
private fun Stepper(label: String, onStep: (Int) -> Unit) {
    val t = LocalTokens.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((sign, by) in listOf("−" to -1, "+" to 1)) {
            if (by > 0) Text(label, Modifier.widthIn(min = 120.dp), fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = t.ink, textAlign = TextAlign.Center)
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(t.soft).combinedClickable(onLongClick = { onStep(by * 7) }) { onStep(by) },
                contentAlignment = Alignment.Center,
            ) { Text(sign, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = t.ink) }
        }
    }
}

/**
 * Connects Google Drive: Google asks the user once, on its own screen, to let LodiSave use its own
 * files in their Drive. Call the answer with what to do once connected.
 */
@Composable
fun rememberDriveConnect(onFail: (String) -> Unit): ((Boolean) -> Unit) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var then by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val token = runCatching { Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(r.data).accessToken }.getOrNull()
        then?.invoke(token != null)
        then = null
    }
    return { next ->
        scope.launch {
            Drive.authorize(ctx).onSuccess { a ->
                val intent = a.pendingIntent
                if (a.hasResolution() && intent != null) { then = next; ask.launch(IntentSenderRequest.Builder(intent.intentSender).build()) }
                else next(true)
            }.onFailure { onFail(it.message ?: "Google Drive") }
        }
    }
}
