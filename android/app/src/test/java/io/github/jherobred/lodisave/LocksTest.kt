package io.github.jherobred.lodisave

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec

class LocksTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    @Test
    fun opensWhatItLocks() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val data = "a photo".toByteArray()
        val sealed = Locks.seal(data, "beach.jpg", "kape123", ByteArray(16) { it.toByte() }, pair.public)
        assertEquals("beach.jpg", Locks.unseal(sealed, listOf("wrong", "kape123"))!!.name)
        assertArrayEquals(data, Locks.unseal(sealed, emptyList(), pair.private)!!.bytes)
        assertNull(Locks.unseal(sealed, listOf("wrong")))
        // Without a recovery key the file carries none, and the flags byte says so.
        val plain = Locks.seal(data, "beach.jpg", "kape123", ByteArray(16), null)
        assertEquals(0, plain[9].toInt())
        assertNull(Locks.unseal(plain, emptyList(), pair.private))
    }

    /** A file the Windows gallery locked (pc-locked.bin, made by its own code) opens here, with its
     *  password and with its recovery key. This side's file goes to build/ for the PC check. */
    @Test
    fun sameFormatAsWindows() {
        val pc = resource("pc-locked.bin")
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(resource("pc-recovery.pk8")))
        assertEquals("hello from the PC gallery", String(Locks.unseal(pc, listOf("kape123"))!!.bytes))
        assertEquals("photo 2.jpg", Locks.unseal(pc, emptyList(), key)!!.name)
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val out = File("build/lock-check").apply { mkdirs() }
        File(out, "android-locked.bin").writeBytes(Locks.seal("hello from the phone".toByteArray(), "clip.mp4", "phone456", ByteArray(16) { 7 }, pair.public))
        File(out, "android-recovery.pk8").writeBytes(pair.private.encoded)
    }

    @Test
    fun totpMatchesTheRfc() {
        val secret = "12345678901234567890".toByteArray()
        for ((t, want) in listOf(59L to 287082, 1111111109L to 81804, 1234567890L to 5924, 2000000000L to 279037)) assertEquals(want, Recovery.code(secret, t / 30))
        assertEquals(1L, Recovery.matches(secret, "287 082", 1))
        assertNull(Recovery.matches(secret, "287082", 3))
        assertEquals("MZXW6YTBOI", Recovery.base32("foobar".toByteArray()))
    }
}
