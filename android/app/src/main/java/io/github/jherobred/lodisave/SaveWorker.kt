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
    data class Done(val uris: List<Uri>, val names: List<String>, val mimes: List<String>) : Job
    data class Failed(val code: String) : Job
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
        return ForegroundInfo(NOTIFICATION, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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
        runCatching { setForeground(foreground("reading", -1.0)) }
        val dir = File(applicationContext.cacheDir, "save-$id").apply { mkdirs() }
        try {
            val zip = Saver.ytdlp(applicationContext) { report("helper", -1.0) }
            if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
            val lodi = Python.getInstance().getModule("lodi")
            val sink = Sink { phase, percent -> report(phase, percent) }
            fun run(format: String?): Pair<List<File>, String?> {
                val r = JSONObject(lodi.callAttr("save", url, dir.path, zip.path, sink, format).toString())
                if (r.has("error")) throw Failure(r.getString("error"))
                val a = r.getJSONArray("files")
                return List(a.length()) { File(a.getString(it)) } to r.optString("join").ifEmpty { null }
            }
            var (files, join) = run(null)
            if (join != null) {
                report("joining", 97.0)
                val out = File(dir, join)
                // Not joinable on this phone: take the best single file that already has sound.
                files = if (Saver.join(files[0], files[1], out)) listOf(out) else run("b").first
            }
            val uris = files.map { Saver.publish(applicationContext, it) }
            val resolver = applicationContext.contentResolver
            Result.success(workDataOf(
                KEY_URIS to uris.map(Uri::toString).toTypedArray(),
                KEY_NAMES to files.map(File::getName).toTypedArray(),
                KEY_MIMES to uris.map { resolver.getType(it).orEmpty() }.toTypedArray(),
            ))
        } catch (f: Failure) {
            Result.failure(workDataOf(KEY_ERROR to f.code))
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName).take(300)))
        } finally {
            dir.deleteRecursively()
        }
    }

    companion object {
        const val CHANNEL = "saving"
        const val NOTIFICATION = 7
        const val KEY_URL = "url"
        const val KEY_PHASE = "phase"
        const val KEY_PERCENT = "percent"
        const val KEY_URIS = "uris"
        const val KEY_NAMES = "names"
        const val KEY_MIMES = "mimes"
        const val KEY_ERROR = "error"

        fun enqueue(ctx: Context, url: String): UUID {
            val request = OneTimeWorkRequestBuilder<SaveWorker>()
                .setInputData(workDataOf(KEY_URL to url))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
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
    WorkInfo.State.SUCCEEDED -> Job.Done(
        outputData.strings(SaveWorker.KEY_URIS).map(Uri::parse),
        outputData.strings(SaveWorker.KEY_NAMES),
        outputData.strings(SaveWorker.KEY_MIMES),
    )
    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> Job.Failed(outputData.getString(SaveWorker.KEY_ERROR) ?: "nothing")
}
