package com.rpax.tpms

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic-only log of the ENTIRE raw BLE advertisement record (not just
 * the manufacturerSpecificData slice BleScannerService normally decodes).
 *
 * Purpose: osmart's app reads a battery-percentage byte at an offset far
 * beyond our current 12-byte manufacturerSpecificData window. This log
 * captures the full scan record so we can check, byte-by-byte, whether
 * there's more data available (e.g. a separate Service Data AD structure)
 * that our current decoder never looks at.
 *
 * Self-contained: does not depend on RawFrameLog's internals.
 */
object FullScanRecordLog {

    private const val MAX_ENTRIES = 300

    private data class Entry(
        val timestampMs: Long,
        val mac: String,
        val rssi: Int,
        val fullBytesHex: String,
        val manufacturerDataHex: String
    )

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun record(mac: String, rssi: Int, fullBytes: ByteArray?, manufacturerData: ByteArray?) {
        if (entries.size >= MAX_ENTRIES) {
            entries.removeFirst()
        }
        entries.addLast(
            Entry(
                timestampMs = System.currentTimeMillis(),
                mac = mac,
                rssi = rssi,
                fullBytesHex = fullBytes?.toHex() ?: "(null)",
                manufacturerDataHex = manufacturerData?.toHex() ?: "(null)"
            )
        )
    }

    @Synchronized
    fun snapshot(): List<String> {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return entries.map { e ->
            "${formatter.format(Date(e.timestampMs))} mac=${e.mac} rssi=${e.rssi} " +
                "fullLen=${(e.fullBytesHex.length + 1) / 3} full=[${e.fullBytesHex}] " +
                "mfgLen=${(e.manufacturerDataHex.length + 1) / 3} mfg=[${e.manufacturerDataHex}]"
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { b -> String.format(Locale.US, "%02X", b) }
}
