package io.github.jherobred.lodisave

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LooksTest {
    /** Against Hugging Face's tokenizer, with the model's tokenizer.json in LODI_ML (skipped without). */
    @Test
    fun tokenizesLikeHuggingFace() {
        val dir = System.getenv("LODI_ML") ?: return
        val tok = Looks.Tokenizer(File(dir, "tokenizer.json").readText())
        assertArrayEquals(longArrayOf(49406, 2368, 49407), tok.encode("cat"))
        assertArrayEquals(longArrayOf(49406, 320, 1125, 539, 320, 3965, 49407), tok.encode("a photo of a mountain"))
        assertArrayEquals(longArrayOf(49406, 1929, 525, 518, 2117, 748, 49407), tok.encode("Dog on the BEACH!!"))
        assertArrayEquals(longArrayOf(49406, 3306, 1002, 568, 49407), tok.encode("hello   world's"))
    }

    /** A 2:1 picture, red on the left half and blue on the right: the middle square is cut out. */
    @Test
    fun preparesAPicture() {
        val (w, h) = 448 to 224
        val px = IntArray(w * h) { if (it % w < w / 2) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val out = Looks.prepare(px, w, h)
        assertEquals(3 * 224 * 224, out.size)
        assertEquals((1f - 0.48145466f) / 0.26862954f, out[0], 1e-3f)
        assertEquals((0f - 0.48145466f) / 0.26862954f, out[223], 1e-3f)
        assertEquals((1f - 0.40821073f) / 0.27577711f, out[2 * 224 * 224 + 223], 1e-3f)
    }
}
