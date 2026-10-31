package io.github.jherobred.lodisave

import java.io.ByteArrayOutputStream

/**
 * Turns a GIF by quarter turns clockwise without changing a colour: each frame's palette indexes are
 * decoded from LZW, turned, and encoded again, so nothing is re-quantized, and timing, looping and
 * transparency stay as they were. Android can decode a GIF but has no encoder for one. Null when the
 * bytes aren't a GIF this can read.
 */
object GifTurn {
    fun turn(gif: ByteArray, turns: Int): ByteArray? = runCatching { Turner(gif, ((turns % 4) + 4) % 4).run() }.getOrNull()

    private class Turner(val src: ByteArray, val turns: Int) {
        var at = 0
        val out = ByteArrayOutputStream(src.size)

        fun u8() = src[at++].toInt() and 0xFF
        fun u16() = u8() or (u8() shl 8)
        fun w16(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
        fun copy(n: Int) { out.write(src, at, n); at += n }

        fun run(): ByteArray {
            val head = String(src, 0, 6, Charsets.US_ASCII)
            require(head == "GIF87a" || head == "GIF89a")
            copy(6)
            val sw = u16()
            val sh = u16()
            val side = turns % 2 == 1
            w16(if (side) sh else sw)
            w16(if (side) sw else sh)
            val packed = u8()
            out.write(packed)
            copy(2) // background colour index, pixel aspect ratio
            if (packed and 0x80 != 0) copy(3 shl ((packed and 7) + 1))
            while (true) {
                when (val b = u8()) {
                    // Extensions (frame timing, looping, comments) stay as they are.
                    0x21 -> { out.write(b); out.write(u8()); copyBlocks() }
                    0x2C -> frame(sw, sh)
                    0x3B -> { out.write(b); return out.toByteArray() }
                    else -> error("block $b at $at")
                }
            }
        }

        fun frame(sw: Int, sh: Int) {
            val l = u16()
            val t = u16()
            val w = u16()
            val h = u16()
            val packed = u8()
            val table = if (packed and 0x80 != 0) src.copyOfRange(at, at + (3 shl ((packed and 7) + 1))).also { at += it.size } else null
            val min = u8()
            val pixels = deinterlace(decode(readBlocks(), min, w * h), w, h, packed and 0x40 != 0)
            // Where the frame lands on the turned screen, and its pixels turned with it.
            val side = turns % 2 == 1
            val nw = if (side) h else w
            val turned = ByteArray(w * h)
            when (turns) {
                1 -> for (y in 0 until h) for (x in 0 until w) turned[x * nw + (h - 1 - y)] = pixels[y * w + x]
                2 -> for (y in 0 until h) for (x in 0 until w) turned[(h - 1 - y) * w + (w - 1 - x)] = pixels[y * w + x]
                3 -> for (y in 0 until h) for (x in 0 until w) turned[(w - 1 - x) * nw + y] = pixels[y * w + x]
                else -> pixels.copyInto(turned)
            }
            val (nl, nt) = when (turns) {
                1 -> sh - t - h to l
                2 -> sw - l - w to sh - t - h
                3 -> t to sw - l - w
                else -> l to t
            }
            out.write(0x2C)
            w16(nl.coerceAtLeast(0))
            w16(nt.coerceAtLeast(0))
            w16(nw)
            w16(if (side) w else h)
            out.write(packed and 0x40.inv()) // written top to bottom now, not interlaced
            table?.let { out.write(it) }
            out.write(min)
            writeBlocks(encode(turned, min))
        }

        // Data comes in sub-blocks: a length byte, that many bytes, until a zero length.
        fun copyBlocks() {
            while (true) {
                val n = u8()
                out.write(n)
                if (n == 0) return
                copy(n)
            }
        }

        fun readBlocks(): ByteArray {
            val data = ByteArrayOutputStream()
            while (true) {
                val n = u8()
                if (n == 0) return data.toByteArray()
                data.write(src, at, n)
                at += n
            }
        }

        fun writeBlocks(data: ByteArray) {
            var i = 0
            while (i < data.size) {
                val n = minOf(255, data.size - i)
                out.write(n)
                out.write(data, i, n)
                i += n
            }
            out.write(0)
        }
    }

    /** An interlaced frame's rows, which come in four passes, back in order from the top. */
    private fun deinterlace(p: ByteArray, w: Int, h: Int, interlaced: Boolean): ByteArray {
        if (!interlaced) return p
        val out = ByteArray(p.size)
        var row = 0
        for ((start, step) in listOf(0 to 8, 4 to 8, 2 to 4, 1 to 2)) {
            var y = start
            while (y < h) {
                p.copyInto(out, y * w, row * w, row * w + w)
                row++
                y += step
            }
        }
        return out
    }

    /** GIF's LZW: `count` palette indexes from codes that start `min` + 1 bits wide and grow to 12. */
    fun decode(data: ByteArray, min: Int, count: Int): ByteArray {
        val out = ByteArray(count)
        val clear = 1 shl min
        val end = clear + 1
        val prefix = IntArray(4096)
        val suffix = ByteArray(4096)
        val first = ByteArray(4096)
        for (i in 0 until clear) { suffix[i] = i.toByte(); first[i] = i.toByte() }
        val stack = ByteArray(4097)
        var size = min + 1
        var next = end + 1
        var old = -1
        var acc = 0
        var bits = 0
        var n = 0
        for (byte in data) {
            acc = acc or ((byte.toInt() and 0xFF) shl bits)
            bits += 8
            while (bits >= size) {
                val code = acc and ((1 shl size) - 1)
                acc = acc ushr size
                bits -= size
                when {
                    code == clear -> { size = min + 1; next = end + 1; old = -1 }
                    code == end -> return out
                    old == -1 -> { if (n < count) out[n++] = suffix[code]; old = code }
                    else -> {
                        var sp = 0
                        var cur = code
                        // The code being defined right now: the last string and its own first index.
                        if (code >= next) { stack[sp++] = first[old]; cur = old }
                        while (cur >= clear) { stack[sp++] = suffix[cur]; cur = prefix[cur] }
                        val head = suffix[cur]
                        stack[sp++] = head
                        while (sp > 0) { val v = stack[--sp]; if (n < count) out[n++] = v }
                        if (next < 4096) {
                            prefix[next] = old
                            suffix[next] = head
                            first[next] = first[old]
                            next++
                            if (next == 1 shl size && size < 12) size++
                        }
                        old = code
                    }
                }
            }
        }
        return out
    }

    /** GIF's LZW the other way, with GIFENCOD's hash table. A full table starts over with a clear code. */
    fun encode(pixels: ByteArray, min: Int): ByteArray {
        val out = ByteArrayOutputStream(pixels.size / 2 + 16)
        val clear = 1 shl min
        val end = clear + 1
        val hsize = 5003
        val htab = IntArray(hsize) { -1 }
        val codes = IntArray(hsize)
        var size = min + 1
        var next = end + 1
        var acc = 0
        var bits = 0
        fun put(code: Int) {
            acc = acc or (code shl bits)
            bits += size
            while (bits >= 8) { out.write(acc and 0xFF); acc = acc ushr 8; bits -= 8 }
        }
        put(clear)
        if (pixels.isNotEmpty()) {
            var ent = pixels[0].toInt() and 0xFF
            for (k in 1 until pixels.size) {
                val c = pixels[k].toInt() and 0xFF
                val key = (c shl 12) + ent
                var i = (c shl 4) xor ent
                if (htab[i] == key) { ent = codes[i]; continue }
                var found = false
                if (htab[i] >= 0) {
                    val disp = if (i == 0) 1 else hsize - i
                    do {
                        i -= disp
                        if (i < 0) i += hsize
                        if (htab[i] == key) { ent = codes[i]; found = true; break }
                    } while (htab[i] >= 0)
                }
                if (found) continue
                put(ent)
                ent = c
                if (next < 4096) {
                    codes[i] = next++
                    htab[i] = key
                    // The decoder adds each code one step later, so it widens when `next` passes the width.
                    if (next > 1 shl size && size < 12) size++
                } else {
                    put(clear)
                    htab.fill(-1)
                    size = min + 1
                    next = end + 1
                }
            }
            put(ent)
        }
        put(end)
        if (bits > 0) out.write(acc and 0xFF)
        return out.toByteArray()
    }
}
