package io.github.jherobred.lodisave

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from the project's GitHub releases: check reads the newest release, install downloads its
 * APK, checks it against the release's SHA256SUMS.txt and hands it to Android's installer, which
 * asks once before replacing the app.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/jherobred/LodiSave/releases/latest"

    data class Release(val version: String, val apk: String, val sums: String?)

    /** "v0.4.1" or "0.4.1-fix1" as numbers, so 0.10.0 sorts after 0.9.0. */
    private fun numbers(v: String) = v.removePrefix("v").split('-', '+').first().split('.').map { it.toIntOrNull() ?: 0 }

    fun newer(latest: String, current: String): Boolean {
        val a = numbers(latest)
        val b = numbers(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "LodiSave")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    /** The newest release when it is newer than this app, or null. Throws when offline. */
    suspend fun check(ctx: Context): Release? = withContext(Dispatchers.IO) {
        val json = open(LATEST).run { try { inputStream.bufferedReader().readText() } finally { disconnect() } }
        val r = JSONObject(json)
        val assets = r.getJSONArray("assets").let { a -> List(a.length()) { a.getJSONObject(it) } }
        val apk = assets.firstOrNull { it.getString("name").endsWith("-android.apk") } ?: return@withContext null
        val sums = assets.firstOrNull { it.getString("name") == "SHA256SUMS.txt" }
        val tag = r.getString("tag_name")
        val current = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
        if (!newer(tag, current)) null
        else Release(tag.removePrefix("v"), apk.getString("browser_download_url"), sums?.getString("browser_download_url"))
    }

    /** Android asks once before LodiSave may install apps: true when that's already allowed. */
    fun allowed(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun askToAllow(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${ctx.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Downloads, checks and installs the release. `progress` goes 0 to 1. */
    suspend fun install(ctx: Context, release: Release, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val name = release.apk.substringAfterLast('/')
        val file = File(ctx.cacheDir, "update.apk")
        open(release.apk).run {
            try {
                val total = contentLengthLong
                inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        var shown = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            if (pct != shown) { shown = pct; progress(pct / 100f) }
                        }
                    }
                }
            } finally { disconnect() }
        }
        // A file that doesn't match its checksum is never installed.
        release.sums?.let { url ->
            val sums = open(url).run { try { inputStream.bufferedReader().readText() } finally { disconnect() } }
            val expected = sums.lineSequence().map { it.trim().split(Regex("\\s+")) }.firstOrNull { it.size == 2 && it[1].removePrefix("*") == name }?.get(0)?.lowercase()
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) } }
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (expected == null || got != expected) { file.delete(); throw Failure("corrupt") }
        }
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(ctx.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("lodisave.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val status = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(status.intentSender)
        }
        file.delete()
    }
}

/** Android's installer reports here; when it needs the user's yes, this shows its prompt. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE) != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let {
            runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
