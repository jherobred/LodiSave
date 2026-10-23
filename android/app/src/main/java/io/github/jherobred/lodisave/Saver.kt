package io.github.jherobred.lodisave

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/** A save that stopped, with a code [Copy.error] explains. */
class Failure(val code: String) : Exception(code)

/** A file LodiSave saved. `added` is in seconds, `duration` in milliseconds, `path` its folder, like
 *  "Pictures/LodiSave/Trips/". Width and height are as it shows, turned photos included. */
data class Saved(
    val uri: Uri, val name: String, val mime: String, val bytes: Long, val added: Long,
    val id: Long = 0, val width: Int = 0, val height: Int = 0, val duration: Long = 0,
    val path: String = "", val favorite: Boolean = false, val trashed: Boolean = false,
) {
    /** The album it's in, a folder inside LodiSave's, or "" for none. */
    val album get() = path.substringAfter("/LodiSave/", "").trimEnd('/')
    /** photo, gif, video or audio. A GIF counts as its own kind, not a photo. */
    val kind get() = when {
        mime == "image/gif" -> "gif"
        mime.startsWith("image/") -> "photo"
        mime.startsWith("audio/") -> "audio"
        else -> "video"
    }
}

object Saver {
    private const val YTDLP = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"
    private const val WEEK = 7L * 24 * 3600 * 1000
    private val ROOTS = listOf(Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_MUSIC)
    // Android renames a file in its trash so; the name is the part after.
    private val TRASHED = Regex("""^\.trashed-\d+-""")

    /**
     * yt-dlp's official zipapp, which Python imports straight from the zip. Sites change often and old
     * versions stop working, so a copy older than a week is replaced, keeping the old one if that fails.
     * While a game is in front the weekly refresh waits for a later save. One save at a time: saves
     * started together wait for the first one's copy.
     */
    @Synchronized
    fun ytdlp(ctx: Context, onFirstDownload: () -> Unit): File {
        val file = File(ctx.filesDir, "yt-dlp.zip")
        if (file.exists() && (System.currentTimeMillis() - file.lastModified() < WEEK || Gaming.on)) return file
        if (!file.exists()) onFirstDownload()
        val part = File(ctx.filesDir, "yt-dlp.part")
        try {
            download(YTDLP, part)
            if (!part.renameTo(file)) {
                file.delete()
                part.renameTo(file)
            }
        } catch (e: Exception) {
            part.delete()
            if (!file.exists()) throw Failure("helper")
        }
        return file
    }

    private fun download(url: String, to: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode != 200) throw Failure("helper")
            c.inputStream.use { input -> to.outputStream().use { input.copyTo(it) } }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Joins a video-only and an audio-only file into one MP4 with Android's own muxer, so HD downloads
     * need no FFmpeg. False when the codecs don't fit an MP4 on this phone, and nothing is written.
     */
    fun join(video: File, audio: File, out: File): Boolean {
        val extractors = mutableListOf<MediaExtractor>()
        var muxer: MediaMuxer? = null
        return try {
            val m = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            val tracks = listOf(video to "video/", audio to "audio/").map { (file, kind) ->
                val ex = MediaExtractor().also { extractors += it }
                ex.setDataSource(file.path)
                val i = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith(kind) }
                ex.selectTrack(i)
                ex to m.addTrack(ex.getTrackFormat(i))
            }
            m.start()
            val buf = ByteBuffer.allocate(8 shl 20)
            val info = MediaCodec.BufferInfo()
            for ((ex, track) in tracks) {
                while (true) {
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    val key = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    info.set(0, n, ex.sampleTime, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    m.writeSampleData(track, buf, info)
                    ex.advance()
                }
            }
            m.stop()
            true
        } catch (e: Exception) {
            out.delete()
            false
        } finally {
            extractors.forEach { it.release() }
            runCatching { muxer?.release() }
        }
    }

    /**
     * Cuts a video to the part from `fromUs` to `toUs` without re-encoding, copying samples as [join]
     * does. A copy can only start on a key frame, so it starts on the one at or before `fromUs`. False
     * when this phone can't write the video's codecs, and nothing is written.
     */
    fun trim(ctx: Context, src: Uri, fromUs: Long, toUs: Long, out: File, webm: Boolean): Boolean {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            ex.setDataSource(ctx, src, null)
            val m = MediaMuxer(out.path, if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            val tracks = mutableMapOf<Int, Int>()
            var video = -1
            for (i in 0 until ex.trackCount) {
                val format = ex.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && video < 0) {
                    video = i
                    // A phone video filmed upright says so here, and the copy has to say it too.
                    if (format.containsKey(MediaFormat.KEY_ROTATION)) m.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                } else if (!mime.startsWith("audio/")) continue
                tracks[i] = m.addTrack(format)
            }
            // The key frame to start from, found with the video alone: every audio frame is a key frame.
            if (video >= 0) {
                ex.selectTrack(video)
                ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            val start = if (video >= 0) ex.sampleTime.coerceAtLeast(0) else fromUs
            tracks.keys.filter { it != video }.forEach(ex::selectTrack)
            ex.seekTo(start, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            m.start()
            val buf = ByteBuffer.allocate(8 shl 20)
            val info = MediaCodec.BufferInfo()
            val past = mutableSetOf<Int>()
            while (past.size < tracks.size) {
                val track = ex.sampleTrackIndex
                if (track < 0) break
                val time = ex.sampleTime
                if (time > toUs) past += track
                else if (time >= start) {
                    val n = ex.readSampleData(buf, 0)
                    val key = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    info.set(0, n, time - start, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    m.writeSampleData(tracks.getValue(track), buf, info)
                }
                ex.advance()
            }
            m.stop()
            true
        } catch (e: Exception) {
            out.delete()
            false
        } finally {
            ex.release()
            runCatching { muxer?.release() }
        }
    }

    fun mimeOf(name: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    /** Adds a file to Pictures, Movies or Music, in a LodiSave folder or an `album` inside it, where the gallery finds it. */
    fun publish(ctx: Context, name: String, mime: String, album: String = "", write: (java.io.OutputStream) -> Unit): Uri {
        val (collection, folder) = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
            mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MUSIC
            else -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
        }
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (album.isEmpty()) "$folder/LodiSave" else "$folder/LodiSave/$album")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw Failure("Couldn't add the file to the gallery.")
        try {
            resolver.openOutputStream(uri)?.use(write) ?: throw Failure("Couldn't write the file.")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    fun publish(ctx: Context, file: File, album: String = ""): Uri = publish(ctx, file.name, mimeOf(file.name), album) { out -> file.inputStream().use { it.copyTo(out) } }

    /** Saves media another app shared, such as a photo it won't let you download. */
    fun publish(ctx: Context, shared: Uri): Uri {
        val resolver = ctx.contentResolver
        val name = resolver.query(shared, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "LodiSave ${System.currentTimeMillis()}"
        val mime = resolver.getType(shared) ?: mimeOf(name)
        val input: InputStream = resolver.openInputStream(shared) ?: throw Failure("nothing")
        return input.use { src -> publish(ctx, name, mime) { src.copyTo(it) } }
    }

    fun nameOf(ctx: Context, uri: Uri): String =
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()

    /** The latest files LodiSave added, newest first, albums included, leaving out ones hidden from home. */
    fun recent(ctx: Context, limit: Int = 9): List<Saved> {
        val hidden = Prefs(ctx).hiddenFromHome
        return media(ctx).filter { "${it.id}" !in hidden }.take(limit)
    }

    /**
     * Every file LodiSave added, newest first: in its LodiSave folders in Pictures, Movies and Music
     * and the albums inside them. Android lets an app read the media it created. `trash` lists the
     * trash instead, MediaStore's on Android 11 and up, LodiSave's own list on Android 10.
     */
    fun media(ctx: Context, trash: Boolean = false): List<Saved> {
        val path = MediaStore.MediaColumns.RELATIVE_PATH
        val cols = listOfNotNull(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.ORIENTATION,
            MediaStore.MediaColumns.DURATION, path,
            if (Build.VERSION.SDK_INT >= 30) MediaStore.MediaColumns.IS_FAVORITE else null,
            if (Build.VERSION.SDK_INT >= 30) MediaStore.MediaColumns.IS_TRASHED else null,
        ).toTypedArray()
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, ROOTS.joinToString(" OR ") { "$path LIKE ?" })
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, ROOTS.map { "$it/LodiSave/%" }.toTypedArray())
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
            if (Build.VERSION.SDK_INT >= 30) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, if (trash) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
        }
        val prefs = Prefs(ctx)
        val favorites = prefs.favorites
        val binned = prefs.trashed
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return ctx.contentResolver.query(files, cols, args, null)?.use { row ->
            buildList {
                while (row.moveToNext()) {
                    val id = row.getLong(0)
                    val inTrash = if (Build.VERSION.SDK_INT >= 30) row.getInt(11) == 1 else "$id" in binned
                    if (inTrash != trash) continue
                    val mime = row.getString(2).orEmpty()
                    // A folder, which MediaStore lists too, has no type.
                    if (mime.isEmpty()) continue
                    val turned = row.getInt(7) % 180 != 0
                    val (w, h) = row.getInt(5) to row.getInt(6)
                    add(Saved(
                        uriOf(id, mime), row.getString(1).orEmpty().replace(TRASHED, ""), mime, row.getLong(3), row.getLong(4), id,
                        if (turned) h else w, if (turned) w else h, row.getLong(8), row.getString(9).orEmpty(),
                        favorite = Build.VERSION.SDK_INT >= 30 && row.getInt(10) == 1 || "$id" in favorites, trashed = inTrash,
                    ))
                }
            }
        }.orEmpty()
    }

    fun uriOf(id: Long, mime: String): Uri = ContentUris.withAppendedId(when {
        mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }, id)
}
