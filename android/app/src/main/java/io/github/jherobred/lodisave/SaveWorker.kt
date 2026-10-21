package io.github.jherobred.lodisave

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** What a save card shows. */
sealed interface Job {
    data object Idle : Job
    data class Working(val phase: String, val percent: Float) : Job
    data class Done(val uris: List<Uri>, val names: List<String>, val mimes: List<String>, val failed: Int = 0) : Job
    data class Failed(val code: String) : Job
    /** A post with several photos or videos: the user picks which to save. */
    data class Choose(val url: String, val kinds: List<String>, val thumbs: List<String>) : Job
}

/**
 * Saves one link in the background, so it finishes even after the share card or the app closes:
 * yt-dlp (or the page's share image) in Python, a MediaMuxer join when the site serves video and
 * audio apart, then the gallery.
 */
class SaveWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    /** Python calls this with progress: report("saving", 42.0). A negative percent means "unknown". */
    fun interface Sink {
        fun report(phase: String, percent: Double)
    }

    private var lastPhase = ""
    private var lastAt = 0L

    override suspend fun getForegroundInfo() = foreground("reading", -1.0)

    private fun foreground(phase: String, percent: Double): ForegroundInfo {
        val ctx = applicationContext
        val copy = Copy.of(ctx)
        ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, copy.notification, NotificationManager.IMPORTANCE_LOW))
        val text = when (phase) {
            "helper" -> copy.helper
            "joining" -> copy.joining
            "saving" -> copy.saving(percent.toFloat())
            else -> copy.reading
        }
        val note = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lodi)
            .setContentTitle(copy.notification)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, percent.toInt().coerceIn(0, 100), percent < 0)
            .build()
        // One notification per save, so saves running side by side don't overwrite each other's.
        return ForegroundInfo(id.hashCode(), note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun report(phase: String, percent: Double) {
        val now = System.currentTimeMillis()
        if (phase == lastPhase && now - lastAt < 250) return
        lastPhase = phase
        lastAt = now
        setProgressAsync(workDataOf(KEY_PHASE to phase, KEY_PERCENT to percent.toFloat()))
        runCatching { setForegroundAsync(foreground(phase, percent)) }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "nothing"))
        val pick = inputData.getString(KEY_PICK)
        runCatching { setForeground(foreground("reading", -1.0)) }
        val ctx = applicationContext
        val dir = File(ctx.cacheDir, "save-$id").apply { mkdirs() }
        // Previews for the picker outlive this save, until the next one tidies up after a day.
        val thumbs = File(ctx.cacheDir, "thumbs").apply { listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.deleteRecursively() } }
        try {
            val zip = Saver.ytdlp(ctx) { report("helper", -1.0) }
            // Saves started together would both start Python, and the second start throws.
            synchronized(SaveWorker) { if (!Python.isStarted()) Python.start(AndroidPlatform(ctx)) }
            val lodi = Python.getInstance().getModule("lodi")
            val sink = Sink { phase, percent -> report(phase, percent) }
            // QuickJS ships as a lib*.so so Android unpacks it executable, for YouTube's challenges.
            val qjs = File(ctx.applicationInfo.nativeLibraryDir, "libqjs.so").path
            fun run(format: String?, items: String?): JSONObject {
                val r = JSONObject(lodi.callAttr("save", url, dir.path, zip.path, sink, format, qjs, items, File(thumbs, "$id").path).toString())
                if (r.has("error")) throw Failure(r.getString("error"))
                return r
            }
            val r = run(null, pick)
            r.optJSONArray("choose")?.let { choose ->
                val items = List(choose.length()) { choose.getJSONObject(it) }
                return@withContext Result.success(workDataOf(
                    KEY_URL to url,
                    KEY_KINDS to items.map { it.getString("kind") }.toTypedArray(),
                    KEY_THUMBS to items.map { it.optString("thumb") }.toTypedArray(),
                ))
            }
            val saves = r.getJSONArray("saves")
            val files = mutableListOf<File>()
            for (i in 0 until saves.length()) {
                val save = saves.getJSONObject(i)
                val parts = save.getJSONArray("files").let { a -> List(a.length()) { File(a.getString(it)) } }
                val join = save.optString("join").ifEmpty { null }
                if (join == null) { files += parts; continue }
                report("joining", 97.0)
                val out = File(dir, join)
                // Not joinable on this phone: take the best single file that already has sound.
                files += if (Saver.join(parts[0], parts[1], out)) listOf(out)
                else run("b", save.getInt("index").toString()).getJSONArray("saves").getJSONObject(0).getJSONArray("files").let { a -> List(a.length()) { File(a.getString(it)) } }
            }
            val uris = files.map { Saver.publish(ctx, it) }
            Result.success(workDataOf(
                KEY_URL to url,
                KEY_URIS to uris.map(Uri::toString).toTypedArray(),
                KEY_NAMES to files.map(File::getName).toTypedArray(),
                KEY_MIMES to uris.map { ctx.contentResolver.getType(it).orEmpty() }.toTypedArray(),
                KEY_FAILED to r.optInt("failed"),
            ))
        } catch (f: Failure) {
            Result.failure(workDataOf(KEY_URL to url, KEY_ERROR to f.code))
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_URL to url, KEY_ERROR to (e.message ?: e.javaClass.simpleName).take(300)))
        } finally {
            dir.deleteRecursively()
        }
    }

    companion object {
        const val CHANNEL = "saving"
        const val TAG = "save"
        const val KEY_URL = "url"
        const val KEY_PICK = "pick"
        const val KEY_PHASE = "phase"
        const val KEY_PERCENT = "percent"
        const val KEY_URIS = "uris"
        const val KEY_NAMES = "names"
        const val KEY_MIMES = "mimes"
        const val KEY_FAILED = "failed"
        const val KEY_KINDS = "kinds"
        const val KEY_THUMBS = "thumbs"
        const val KEY_ERROR = "error"

        /** Saves a link. `pick` names the items of a post to save ("0,2"); without it a post with
         *  several asks which to save. Saves run side by side. */
        fun enqueue(ctx: Context, url: String, pick: String? = null): UUID {
            val request = OneTimeWorkRequestBuilder<SaveWorker>()
                .setInputData(workDataOf(KEY_URL to url, KEY_PICK to pick))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(TAG)
                .addTag("url:$url")
                .build()
            WorkManager.getInstance(ctx).enqueue(request)
            return request.id
        }
    }
}

private fun androidx.work.Data.strings(key: String) = getNullableStringArray(key).orEmpty().map { it.orEmpty() }

fun WorkInfo?.toJob(): Job = when (this?.state) {
    null, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> Job.Working("reading", -1f)
    WorkInfo.State.RUNNING -> Job.Working(progress.getString(SaveWorker.KEY_PHASE) ?: "reading", progress.getFloat(SaveWorker.KEY_PERCENT, -1f))
    WorkInfo.State.SUCCEEDED -> if (outputData.keyValueMap.containsKey(SaveWorker.KEY_KINDS)) Job.Choose(
        outputData.getString(SaveWorker.KEY_URL).orEmpty(),
        outputData.strings(SaveWorker.KEY_KINDS),
        outputData.strings(SaveWorker.KEY_THUMBS),
    ) else Job.Done(
        outputData.strings(SaveWorker.KEY_URIS).map(Uri::parse),
        outputData.strings(SaveWorker.KEY_NAMES),
        outputData.strings(SaveWorker.KEY_MIMES),
        outputData.getInt(SaveWorker.KEY_FAILED, 0),
    )
    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> Job.Failed(outputData.getString(SaveWorker.KEY_ERROR) ?: "nothing")
}

/** The link a save is for. */
val WorkInfo.link: String get() = tags.firstOrNull { it.startsWith("url:") }?.removePrefix("url:").orEmpty()
