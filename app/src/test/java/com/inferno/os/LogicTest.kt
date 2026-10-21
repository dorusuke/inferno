package com.inferno.os

import org.junit.Assert.*
import org.junit.Test

class LogicTest {
    @Test fun wakeHeardVariants() {
        assertTrue(WakeText.isWake("Hey Inferno"))
        assertTrue(WakeText.isWake("hey, inferno what time is it"))
        assertTrue(WakeText.isWake("hey in for no"))
        assertTrue(WakeText.isWake("infer no"))
        assertTrue(WakeText.isWake("Infernoo please"))
    }

    @Test fun wakeRejectsOrdinaryTalk() {
        assertFalse(WakeText.isWake("what is the information for the night"))
        assertFalse(WakeText.isWake("in the end it was fine"))
        assertFalse(WakeText.isWake("the internet is down"))
        assertFalse(WakeText.isWake(""))
    }

    @Test fun stripWake() {
        assertEquals("what time is it", WakeText.stripWake("Hey Inferno, what time is it?"))
        assertEquals("call mom", WakeText.stripWake("hey in for no call mom"))
        assertEquals("", WakeText.stripWake("Hey Inferno"))
        assertEquals("what is the weather", WakeText.stripWake("what is the weather"))
    }

    @Test fun gateSkipsSteadyNoiseAndOpensOnSpeech() {
        val g = EnergyGate()
        repeat(50) { assertFalse(g.step(200.0).feed) }
        assertTrue(g.step(3000.0).feed)
        // hangover keeps feeding briefly after speech stops
        assertTrue(g.step(100.0).feed)
        var closed = false
        repeat(20) { if (g.step(100.0).closed) closed = true }
        assertTrue(closed)
        assertFalse(g.step(100.0).feed)
    }

    @Test fun gateAdaptsToLoudRoom() {
        val g = EnergyGate()
        repeat(300) { g.step(1200.0) }
        assertFalse("steady loud noise should stop triggering", g.step(1300.0).feed)
        assertTrue(g.step(9000.0).feed)
    }

    @Test fun wavRoundTrip() {
        val pcm = shortArrayOf(0, 1, -1, 12345, -32768, 32767)
        val wav = Pcm.toWav(pcm, 16000)
        assertEquals(44 + pcm.size * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertArrayEquals(pcm, Pcm.fromWav(wav))
    }

    @Test fun rmsAndConcat() {
        assertEquals(0.0, Pcm.rms(ShortArray(10)), 0.0)
        assertEquals(1000.0, Pcm.rms(ShortArray(8) { 1000 }), 0.001)
        assertArrayEquals(shortArrayOf(1, 2, 3), Pcm.concat(listOf(shortArrayOf(1), shortArrayOf(2, 3))))
    }

    @Test fun floatConversionClamps() {
        val s = Pcm.floatToShort(floatArrayOf(0f, 1f, -1f, 2f, -2f))
        assertEquals(0, s[0].toInt()); assertEquals(32767, s[1].toInt())
        assertEquals(-32767, s[2].toInt()); assertEquals(32767, s[3].toInt())
    }

    @Test fun sentenceSplit() {
        assertEquals(listOf("Hello there.", "How are you?", "Fine!"), Sentences.split("Hello there. How are you?  Fine!"))
        val long = "word ".repeat(100).trim()
        val parts = Sentences.split(long, 100)
        assertTrue(parts.all { it.length <= 100 })
        assertEquals(long, parts.joinToString(" "))
        assertTrue(Sentences.split("   ").isEmpty())
    }
}
