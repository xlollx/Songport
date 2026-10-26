package com.xlollx.songport.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {

    /** "Hello" in byte mode, version 1-M: mode, count, five bytes, terminator, then 0xEC/0x11 up to 16 codewords. */
    @Test fun dataCodewordsOfHello() {
        val expected = byteArrayOf(
            0x40, 0x54, 0x86.toByte(), 0x56, 0xC6.toByte(), 0xC6.toByte(), 0xF0.toByte(),
            0xEC.toByte(), 0x11, 0xEC.toByte(), 0x11, 0xEC.toByte(), 0x11, 0xEC.toByte(), 0x11, 0xEC.toByte(),
        )
        assertArrayEquals(expected, QrCode.dataCodewords("Hello".toByteArray(), 1))
    }

    @Test fun totalCodewordsMatchTheStandard() {
        assertEquals(listOf(26, 44, 70, 100, 134, 172, 196, 242, 292, 346), (1..10).map { QrCode.totalCodewords(it) })
        // Data capacity in bytes at level M, from the standard's table.
        assertEquals(listOf(14, 26, 42, 62, 84, 106, 122, 152, 180, 213), (1..10).map { QrCode.capacity(it) })
    }

    /** A codeword block with its Reed–Solomon remainder is divisible by the generator: zero at every root. */
    @Test fun reedSolomonRemainderMakesTheBlockDivisible() {
        val data = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M".toByteArray()
        val ec = QrCode.ReedSolomon.remainder(data, 18)
        val poly = (data + ec).map { it.toInt() and 0xFF }
        for (i in 0 until 18) {
            val root = QrCode.ReedSolomon.pow(2, i)
            var acc = 0
            for (c in poly) acc = QrCode.ReedSolomon.mul(acc, root) xor c
            assertEquals("root $i", 0, acc)
        }
        // The generator for 10 codewords, as printed in the standard's annex.
        assertArrayEquals(intArrayOf(216, 194, 159, 111, 199, 94, 95, 113, 157, 193), QrCode.ReedSolomon.generator(10))
    }

    @Test fun formatAndVersionBits() {
        assertEquals(0b101010000010010, QrCode.formatBits(0)) // level M, mask 0
        assertEquals(0b101000100100101, QrCode.formatBits(1)) // level M, mask 1
        assertEquals(0b000111110010010100, QrCode.versionBits(7))
        assertEquals(0b001010010011010011, QrCode.versionBits(10))
    }

    @Test fun smallestVersionThatFits() {
        assertEquals(1, QrCode.encode("Hello").version)
        assertEquals(4, QrCode.encode("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M").version) // 56 bytes
        assertEquals(10, QrCode.encode("x".repeat(213)).version)
        assertTrue(runCatching { QrCode.encode("x".repeat(214)) }.isFailure)
    }

    @Test fun symbolHasFindersTimingAndFormat() {
        val s = QrCode.encode("https://tidal.com/browse/playlist/0b8b8a2f-6c1e-4b3b-9f6b-1c2d3e4f5a6b")
        assertEquals(s.version * 4 + 17, s.size)
        val n = s.size
        // Finder patterns: dark centre, light ring, dark ring, light separator.
        for ((cx, cy) in listOf(3 to 3, n - 4 to 3, 3 to n - 4)) {
            assertTrue(s[cx, cy]); assertFalse(s[cx + 2, cy]); assertTrue(s[cx + 3, cy]); assertFalse(s[cx, cy - 2]); assertTrue(s[cx, cy - 3])
        }
        // Separators: the light ring around the top-left finder.
        for (i in 0..7) { assertFalse(s[7, i]); assertFalse(s[i, 7]) }
        // Timing pattern alternates along row and column 6 between the finders.
        for (i in 8 until n - 8) { assertEquals(i % 2 == 0, s[i, 6]); assertEquals(i % 2 == 0, s[6, i]) }
        // The dark module.
        assertTrue(s[8, n - 8])
        // Both copies of the format information say the same thing, and it is the mask the symbol used.
        val expected = QrCode.formatBits(s.mask)
        var copy1 = 0
        for (i in 0..5) if (s[8, i]) copy1 = copy1 or (1 shl i)
        if (s[8, 7]) copy1 = copy1 or (1 shl 6)
        if (s[8, 8]) copy1 = copy1 or (1 shl 7)
        if (s[7, 8]) copy1 = copy1 or (1 shl 8)
        for (i in 9..14) if (s[14 - i, 8]) copy1 = copy1 or (1 shl i)
        var copy2 = 0
        for (i in 0..7) if (s[n - 1 - i, 8]) copy2 = copy2 or (1 shl i)
        for (i in 8..14) if (s[8, n - 15 + i]) copy2 = copy2 or (1 shl i)
        assertEquals(expected, copy1)
        assertEquals(expected, copy2)
    }

    @Test fun versionSevenCarriesVersionInformation() {
        val s = QrCode.encode("x".repeat(110))
        assertEquals(7, s.version)
        val bits = QrCode.versionBits(7)
        val n = s.size
        for (i in 0 until 18) {
            val bit = (bits ushr i) and 1 == 1
            assertEquals(bit, s[n - 11 + i % 3, i / 3])
            assertEquals(bit, s[i / 3, n - 11 + i % 3])
        }
    }
}
