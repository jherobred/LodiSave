package io.github.jherobred.lodisave

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Finding photos and videos by what's in them: "cat" or "mountain" finds cats and mountains, whatever
 *  the files are called. OpenAI's CLIP (MIT license) turns each picture, from the phone's own
 *  thumbnail of it, into a list of numbers, and the words typed into another; the closer the two, the
 *  better the match. It all runs on the phone: nothing about the pictures or the words leaves it. The
 *  model comes from Hugging Face the first time it's turned on, about 150 MB, each file checked
 *  against the size and SHA-256 written here; the same model and the same scoring as on PC. */
object Looks {
    private const val BASE = "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/6b7c523703ae26e39be4b03774966f8571c7f523/"
    private class Model(val name: String, val path: String, val size: Long, val sha: String)
    private val FILES = listOf(
        Model("tokenizer.json", "tokenizer.json", 2_224_119, "f7f3b7af117d467b58374797691a6438d3e6b9e9cef800dfd5dced7f697a90cd"),
        Model("text.onnx", "onnx/text_model_quantized.onnx", 64_504_507, "73baab855d406190da9faa498cfedf65f15cf309f4cc7385b7b032e6d08e5c3a"),
        Model("vision.onnx", "onnx/vision_model_quantized.onnx", 89_117_001, "583fd1110a514667812fee7d684952aaf82a99b959760c8d7dca7e0ab9839299"),
    )
    private const val DIM = 512
    private const val SIDE = 224
    /** How far a picture must be closer to the words than to "a photo" to count, as on PC. */
    private const val ENOUGH = 0.02f
    /** What any photo is near, taken away from the words so only what they add counts. */
    private val PLAIN = listOf("a photo", "a picture", "an image", "a photo of something", "a screenshot")
    /** Common Tagalog words, in English for the model, which knows English best. */
    private val TAGALOG = mapOf(
        "pusa" to "cat", "aso" to "dog", "bundok" to "mountain", "dagat" to "sea", "pagkain" to "food", "bulaklak" to "flower", "kotse" to "car",
        "sasakyan" to "car", "bahay" to "house", "langit" to "sky", "ulap" to "clouds", "araw" to "sun", "buwan" to "moon", "puno" to "tree",
        "ibon" to "bird", "isda" to "fish", "tao" to "person", "bata" to "child", "sanggol" to "baby", "lalaki" to "man", "babae" to "woman",
        "dalampasigan" to "beach", "baybayin" to "beach", "ilog" to "river", "lungsod" to "city", "kalye" to "street", "gabi" to "night",
        "ulan" to "rain", "damit" to "clothes", "sapatos" to "shoes", "kain" to "food", "prutas" to "fruit", "gulay" to "vegetables",
        "kanin" to "rice", "simbahan" to "church", "paaralan" to "school", "halaman" to "plant", "kabayo" to "horse", "manok" to "chicken", "kalabaw" to "water buffalo",
    )

    // What the screens show.
    /** Getting the model: how much is here, 0 to 1. */
    var getting by mutableStateOf<Float?>(null); private set
    var failed by mutableStateOf(false); private set
    var ready by mutableStateOf(false); private set
    var done by mutableIntStateOf(0); private set
    var total by mutableIntStateOf(0); private set

    private val lock = Object()
    /** A file's id to its size and time, and its numbers. */
    private val index = HashMap<Long, Pair<Long, ShortArray>>()
    private var wanted: List<Saved> = emptyList()
    private val skipped = HashSet<Long>()
    private var loaded = false
    private var unsaved = 0
    private var running = false
    private var text: Text? = null
    private var textUsed = 0L

    private class Text(val session: OrtSession, val tok: Tokenizer, val plain: FloatArray)

    private fun dir(ctx: Context) = File(ctx.filesDir, "search")
    private fun isReady(ctx: Context) = FILES.all { File(dir(ctx), it.name).length() == it.size }
    private fun stamp(s: Saved) = s.bytes * 1_000_003 + s.added

    /** The photos and videos the app shows, to work out the ones not done yet. Starts working
     *  through them when the model is here. */
    fun want(ctx: Context, items: Collection<Saved>) {
        val pictures = items.filter { it.kind != "audio" && !it.trashed }
        synchronized(lock) {
            wanted = pictures
            if (!loaded) { load(ctx); loaded = true }
            count()
            lock.notifyAll()
        }
        ready = isReady(ctx)
        if (ready) start(ctx)
    }

    /** Turns it on: gets the model, then looks through the photos. */
    fun setup(ctx: Context) {
        failed = false
        start(ctx)
    }

    private fun count() {
        val ids = wanted.associate { it.id to stamp(it) }
        done = ids.count { (id, st) -> index[id]?.first == st || id in skipped }
        total = ids.size
    }

    private fun start(context: Context) {
        val ctx = context.applicationContext
        synchronized(lock) {
            if (running) return lock.notifyAll()
            running = true
        }
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                if (!isReady(ctx)) download(ctx)
                ready = true
                work(ctx)
            } catch (_: Throwable) {
                failed = true
            } finally {
                getting = null
                synchronized(lock) { running = false }
            }
        }.apply { name = "lodisave-looks" }.start()
    }

    private fun download(ctx: Context) {
        val dir = dir(ctx).apply { mkdirs() }
        val all = FILES.sumOf { it.size }.toFloat()
        var before = 0L
        getting = 0f
        for (m in FILES) {
            val to = File(dir, m.name)
            if (to.length() != m.size) {
                val part = File(dir, "${m.name}.part")
                var tries = 0
                while (part.length() < m.size) {
                    val c = URL(BASE + m.path).openConnection() as HttpURLConnection
                    c.connectTimeout = 30_000
                    c.readTimeout = 60_000
                    if (part.length() > 0) c.setRequestProperty("Range", "bytes=${part.length()}-")
                    try {
                        val append = c.responseCode == 206
                        if (c.responseCode != 200 && !append) error("download")
                        c.inputStream.use { input ->
                            java.io.FileOutputStream(part, append).use { out ->
                                val buf = ByteArray(1 shl 16)
                                var since = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    since += n
                                    if (since > 1 shl 20) { since = 0; getting = (before + part.length()) / all }
                                }
                            }
                        }
                    } catch (e: java.io.IOException) {
                        if (++tries >= 3) throw e
                    } finally {
                        c.disconnect()
                    }
                }
                val sha = MessageDigest.getInstance("SHA-256")
                part.inputStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) { val n = input.read(buf); if (n < 0) break; sha.update(buf, 0, n) }
                }
                if (part.length() != m.size || sha.digest().joinToString("") { "%02x".format(it) } != m.sha) {
                    part.delete()
                    error("check")
                }
                part.renameTo(to)
            }
            before += m.size
            getting = before / all
        }
        getting = null
    }

    /** Works out the pictures not done yet, a few at a time, then waits for more. */
    private fun work(ctx: Context) {
        val env = environment()
        var vision: OrtSession? = null
        while (true) {
            val todo = synchronized(lock) {
                val todo = wanted.filter { it.id !in skipped && index[it.id]?.first != stamp(it) }.take(4)
                if (todo.isEmpty()) {
                    // Done for now: the model's memory goes back, and the index is kept.
                    vision?.close(); vision = null
                    if (unsaved > 0) save(ctx)
                    if (text != null && System.currentTimeMillis() - textUsed > 600_000) { text?.session?.close(); text = null }
                    count()
                    lock.wait(30_000)
                }
                todo
            }
            if (todo.isEmpty()) continue
            if (vision == null) vision = env.createSession(File(dir(ctx), "vision.onnx").path, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })
            val pixels = FloatArray(todo.size * 3 * SIDE * SIDE)
            val got = ArrayList<Saved>()
            for (item in todo) {
                val bmp = runCatching {
                    if (item.cloud.isNotEmpty()) runBlocking { Drive.thumb(ctx, item, 448) }
                    else ctx.contentResolver.loadThumbnail(item.uri, Size(448, 448), null)
                }.getOrNull()
                if (bmp == null || bmp.width == 0 || bmp.height == 0) { synchronized(lock) { skipped += item.id }; continue }
                val soft = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
                val px = IntArray(soft.width * soft.height)
                soft.getPixels(px, 0, soft.width, 0, 0, soft.width, soft.height)
                prepare(px, soft.width, soft.height).copyInto(pixels, got.size * 3 * SIDE * SIDE)
                got += item
            }
            if (got.isEmpty()) continue
            val vs = runCatching {
                OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels, 0, got.size * 3 * SIDE * SIDE), longArrayOf(got.size.toLong(), 3, SIDE.toLong(), SIDE.toLong())).use { input ->
                    vision!!.run(mapOf("pixel_values" to input)).use { out -> rows(out.get("image_embeds").get() as OnnxTensor) }
                }
            }.getOrNull()
            synchronized(lock) {
                if (vs == null) skipped += got.map { it.id }
                else got.forEachIndexed { i, item -> index[item.id] = stamp(item) to pack(vs[i]); unsaved++ }
                if (unsaved >= 64) save(ctx)
                count()
            }
        }
    }

    /** The pictures that look like `query`, id to how well. Empty while the model isn't here. */
    fun find(ctx: Context, query: String): Map<Long, Float> {
        if (!isReady(ctx)) return emptyMap()
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { TAGALOG[it.lowercase()] ?: it }
        if (words.isEmpty()) return emptyMap()
        val q = synchronized(lock) {
            val t = text ?: run {
                val env = environment()
                val tok = Tokenizer(File(dir(ctx), "tokenizer.json").readText())
                val session = env.createSession(File(dir(ctx), "text.onnx").path, OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) })
                val plain = FloatArray(DIM)
                for (p in PLAIN) embedText(session, tok.encode(p)).forEachIndexed { i, x -> plain[i] += x / PLAIN.size }
                Text(session, tok, plain).also { text = it }
            }
            textUsed = System.currentTimeMillis()
            val v = embedText(t.session, t.tok.encode("a photo of $words"))
            FloatArray(DIM) { v[it] - t.plain[it] }
        }
        val hits = HashMap<Long, Float>()
        synchronized(lock) {
            for ((id, entry) in index) {
                var s = 0f
                val v = entry.second
                for (i in 0 until DIM) s += v[i] / 32767f * q[i]
                if (s >= ENOUGH) hits[id] = s
            }
        }
        return hits
    }

    /** ONNX Runtime, with its usage reports to Microsoft off. */
    private fun environment(): OrtEnvironment = OrtEnvironment.getEnvironment().also { runCatching { it.setTelemetry(false) } }

    private fun embedText(session: OrtSession, ids: LongArray): FloatArray {
        val env = environment()
        return OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { input ->
            session.run(mapOf("input_ids" to input)).use { out -> rows(out.get("text_embeds").get() as OnnxTensor)[0] }
        }
    }

    private fun rows(t: OnnxTensor): List<FloatArray> {
        val b = t.floatBuffer
        return List(b.remaining() / DIM) { r -> unit(FloatArray(DIM) { b.get(r * DIM + it) }) }
    }

    private fun unit(v: FloatArray): FloatArray {
        val n = max(sqrt(v.sumOf { (it * it).toDouble() }).toFloat(), 1e-12f)
        return FloatArray(v.size) { v[it] / n }
    }

    /** A unit vector in half the space, each number to within 1/32767. */
    private fun pack(v: FloatArray) = ShortArray(v.size) { Math.round(v[it].coerceIn(-1f, 1f) * 32767f).toShort() }

    private fun save(ctx: Context) {
        unsaved = 0
        val dir = dir(ctx).apply { mkdirs() }
        val part = File(dir, "index.part")
        runCatching {
            DataOutputStream(part.outputStream().buffered()).use { out ->
                out.writeInt(0x4C534131) // "LSA1"
                out.writeInt(index.size)
                for ((id, e) in index) {
                    out.writeLong(id)
                    out.writeLong(e.first)
                    for (x in e.second) out.writeShort(x.toInt())
                }
            }
            part.renameTo(File(dir, "index.bin"))
        }
    }

    private fun load(ctx: Context) {
        runCatching {
            DataInputStream(File(dir(ctx), "index.bin").inputStream().buffered()).use { input ->
                if (input.readInt() != 0x4C534131) return
                repeat(input.readInt()) {
                    val id = input.readLong()
                    val st = input.readLong()
                    index[id] = st to ShortArray(DIM) { input.readShort() }
                }
            }
        }
    }

    /** ARGB pixels as the model takes them, as CLIP's own preparation does: the short side to 224 by
     *  bicubic, the middle 224 by 224, each colour scaled and centred, in planes of red, green and blue. */
    internal fun prepare(px: IntArray, w: Int, h: Int): FloatArray {
        val (nw, nh) = if (w <= h) SIDE to max(h * SIDE / w, SIDE) else max(w * SIDE / h, SIDE) to SIDE
        val rgb = FloatArray(w * h * 3) { val p = px[it / 3]; ((p shr (16 - 8 * (it % 3))) and 0xFF).toFloat() }
        val across = resample(rgb, w, h, nw, true)
        val full = resample(across, nw, h, nh, false)
        val left = (nw - SIDE) / 2
        val top = (nh - SIDE) / 2
        val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        val std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
        val out = FloatArray(3 * SIDE * SIDE)
        for (y in 0 until SIDE) for (x in 0 until SIDE) {
            val p = ((top + y) * nw + left + x) * 3
            for (c in 0 until 3) out[c * SIDE * SIDE + y * SIDE + x] = (full[p + c] / 255f - mean[c]) / std[c]
        }
        return out
    }

    /** Resizes one way with a bicubic filter widened when shrinking, as Pillow does, rounding to
     *  whole 0 to 255 values between the two ways as it does. */
    private fun resample(px: FloatArray, w: Int, h: Int, to: Int, across: Boolean): FloatArray {
        val from = if (across) w else h
        val scale = from.toFloat() / to
        val wide = max(scale, 1f)
        val support = 2f * wide
        fun cubic(v: Float): Float {
            val a = -0.5f
            val x = abs(v)
            return if (x < 1f) ((a + 2f) * x - (a + 3f)) * x * x + 1f else if (x < 2f) (((x - 5f) * x + 8f) * x - 4f) * a else 0f
        }
        val starts = IntArray(to)
        val weights = Array(to) { i ->
            val center = (i + 0.5f) * scale
            val lo = max(floor(center - support + 0.5f).toInt(), 0)
            val hi = min(floor(center + support + 0.5f).toInt(), from)
            starts[i] = lo
            val k = FloatArray(hi - lo) { cubic((lo + it - center + 0.5f) / wide) }
            val sum = k.sum()
            if (sum != 0f) for (j in k.indices) k[j] /= sum
            k
        }
        val ow = if (across) to else w
        val oh = if (across) h else to
        val out = FloatArray(ow * oh * 3)
        for (y in 0 until oh) for (x in 0 until ow) {
            val i = if (across) x else y
            val k = weights[i]
            val lo = starts[i]
            var r = 0f; var g = 0f; var b = 0f
            for (n in k.indices) {
                val p = if (across) (y * w + lo + n) * 3 else ((lo + n) * w + x) * 3
                r += px[p] * k[n]; g += px[p + 1] * k[n]; b += px[p + 2] * k[n]
            }
            val o = (y * ow + x) * 3
            out[o] = Math.round(r).coerceIn(0, 255).toFloat()
            out[o + 1] = Math.round(g).coerceIn(0, 255).toFloat()
            out[o + 2] = Math.round(b).coerceIn(0, 255).toFloat()
        }
        return out
    }

    /** CLIP's tokenizer: lower case, split into words, numbers and marks, then each into the pieces
     *  its vocabulary has by byte-pair merges, between start and end marks. */
    internal class Tokenizer(json: String) {
        private val vocab = HashMap<String, Long>()
        private val ranks = HashMap<String, Int>()
        private val bytes = CharArray(256)
        private val split = Regex("""<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""")

        init {
            val model = JSONObject(json).getJSONObject("model")
            val v = model.getJSONObject("vocab")
            for (k in v.keys()) vocab[k] = v.getLong(k)
            val merges = model.getJSONArray("merges")
            for (i in 0 until merges.length()) {
                val m = merges.get(i)
                val pair = if (m is JSONArray) "${m.getString(0)} ${m.getString(1)}" else m as String
                ranks[pair] = i
            }
            // GPT-2's bytes as printable characters.
            var extra = 0
            for (b in 0 until 256) {
                val shown = b in 33..126 || b in 161..172 || b in 174..255
                bytes[b] = if (shown) b.toChar() else (256 + extra++).toChar()
            }
        }

        fun encode(text: String): LongArray {
            val clean = text.trim().split(Regex("\\s+")).joinToString(" ").lowercase()
            val ids = arrayListOf(49406L)
            for (word in split.findAll(clean)) {
                val mapped = word.value.toByteArray().joinToString("") { bytes[it.toInt() and 0xFF].toString() }
                bpe(mapped).mapNotNullTo(ids) { vocab[it] }
            }
            return (ids.take(76) + 49407L).toLongArray()
        }

        private fun bpe(word: String): List<String> {
            var parts = word.map { it.toString() }.toMutableList()
            parts[parts.lastIndex] = parts.last() + "</w>"
            while (parts.size > 1) {
                var best = -1; var at = -1
                for (i in 0 until parts.size - 1) {
                    val r = ranks["${parts[i]} ${parts[i + 1]}"] ?: continue
                    if (best < 0 || r < best) { best = r; at = i }
                }
                if (at < 0) break
                val a = parts[at]; val b = parts[at + 1]
                val merged = ArrayList<String>(parts.size)
                var i = 0
                while (i < parts.size) {
                    if (i + 1 < parts.size && parts[i] == a && parts[i + 1] == b) { merged += a + b; i += 2 } else { merged += parts[i]; i++ }
                }
                parts = merged
            }
            return parts
        }
    }
}
