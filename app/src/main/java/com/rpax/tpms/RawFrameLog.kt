package com.rpax.tpms

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps a rolling in-memory log of raw decoded BLE frames (both sensors),
 * so real captured data can be exported and used to verify or correct the
 * byte-layout assumptions in TpmsDecoder against actual hardware -- instead
 * of guessing further from the spec alone.
 */
object RawFrameLog {
    private const val MAX_ENTRIES = 1000
    private val entries = ArrayDeque<String>()
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun record(position: TpmsDecoder.Position, mac: String, data: ByteArray, pressureBar: Float, tempC: Int) {
        val hex = data.joinToString(" ") { String.format(Locale.US, "%02X", it) }
        val line = "${formatter.format(Date())} | $mac | $position | bytes=[$hex] | " +
            "decoded_pressure=${String.format(Locale.US, "%.2f", pressureBar)} bar | decoded_temp=$tempC C"
        entries.addLast(line)
        if (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()
}
