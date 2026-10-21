package io.github.jherobred.lodisave

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
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

data class Saved(val uri: Uri, val name: String, val mime: String, val bytes: Long, val added: Long)

object Saver {
    private const val YTDLP = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"
    private const val WEEK = 7L * 24 * 3600 * 1000

    /**
     * yt-dlp's official zipapp, which Python imports straight from the zip. Sites change often and old
     * versions stop working, so a copy older than a week is replaced, keeping the old one if that fails.
     * One save at a time: saves started together wait for the first one's copy.
     */
    @Synchronized
    fun ytdlp(ctx: Context, onFirstDownload: () -> Unit): File {
        val file = File(ctx.filesDir, "yt-dlp.zip")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < WEEK) return file
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

    private fun mimeOf(name: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    /** Adds a file to Pictures, Movies or Music, in a LodiSave folder, where the gallery finds it. */
    fun publish(ctx: Context, name: String, mime: String, write: (java.io.OutputStream) -> Unit): Uri {
        val (collection, folder) = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
            mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MUSIC
            else -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
        }
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/LodiSave")
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

    fun publish(ctx: Context, file: File): Uri = publish(ctx, file.name, mimeOf(file.name)) { out -> file.inputStream().use { it.copyTo(out) } }

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

    /** The latest files LodiSave added, newest first. Android lets an app read the media it created. */
    fun recent(ctx: Context, limit: Int = 9): List<Saved> {
        val folders = listOf(Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_MUSIC).map { "$it/LodiSave/" }
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED)
        val where = "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?, ?)"
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return ctx.contentResolver.query(files, cols, where, folders.toTypedArray(), "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            buildList {
                while (c.moveToNext() && size < limit) {
                    val mime = c.getString(2).orEmpty()
                    val base = when {
                        mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        else -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    }
                    add(Saved(ContentUris.withAppendedId(base, c.getLong(0)), c.getString(1).orEmpty(), mime, c.getLong(3), c.getLong(4)))
                }
            }
        }.orEmpty()
    }
}
