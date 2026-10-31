package io.github.jherobred.lodisave

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class GifTurnTest {
    @Test
    fun lzwRoundTrips() {
        // Long enough to fill the code table and start over with a clear code.
        val noise = ByteArray(300_000) { ((it * 7919) xor (it shr 3)).toByte() }
        assertArrayEquals(noise, GifTurn.decode(GifTurn.encode(noise, 8), 8, noise.size))
        val few = ByteArray(50_000) { (it % 4).toByte() }
        assertArrayEquals(few, GifTurn.decode(GifTurn.encode(few, 2), 2, few.size))
    }

    @Test
    fun turnsAFrameAndItsPlace() {
        // A 4x2 screen with a 3x2 frame at (1, 0): indexes 0..5 row by row.
        val pixels = byteArrayOf(0, 1, 2, 3, 4, 5)
        val out = ByteArrayOutputStream()
        fun w16(v: Int) { out.write(v and 0xFF); out.write(v shr 8) }
        out.write("GIF89a".toByteArray())
        w16(4); w16(2); out.write(0x81); out.write(0); out.write(0) // a 4-colour global table follows
        out.write(ByteArray(12))
        out.write(0x2C); w16(1); w16(0); w16(3); w16(2); out.write(0)
        out.write(2)
        val data = GifTurn.encode(pixels, 2)
        out.write(data.size); out.write(data); out.write(0)
        out.write(0x3B)

        val turned = GifTurn.turn(out.toByteArray(), 1)!!
        val u16 = { at: Int -> (turned[at].toInt() and 0xFF) or ((turned[at + 1].toInt() and 0xFF) shl 8) }
        assertEquals(listOf(2, 4), listOf(u16(6), u16(8))) // the screen is 2 wide, 4 tall now
        val frame = 13 + 12
        assertEquals(listOf(0, 1, 2, 3), listOf(u16(frame + 1), u16(frame + 3), u16(frame + 5), u16(frame + 7)))
        val size = turned[frame + 11].toInt() and 0xFF
        val back = GifTurn.decode(turned.copyOfRange(frame + 12, frame + 12 + size), 2, 6)
        // Turned clockwise: the bottom row becomes the left column.
        assertArrayEquals(byteArrayOf(3, 0, 4, 1, 5, 2), back)
    }
}
