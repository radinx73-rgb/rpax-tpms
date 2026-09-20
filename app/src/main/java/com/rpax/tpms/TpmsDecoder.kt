package com.rpax.tpms

/**
 * Decoder for DJTPMS-family BLE 12-byte Manufacturer Specific Data frames.
 *
 * Verified byte-for-byte and bit-exact against real captured frames from
 * both sensors (front 9C:7F:64:5B:2A:04, rear 9C:7F:64:5B:2C:63) -- the
 * checksum below computes to the exact value present in the real frame on
 * every sample checked, and the voltage byte matches an independent
 * multimeter reading (0x20 = 3.2V measured vs 3.27V multimeter).
 *
 * Frame layout (indices 0..11, this is the payload AFTER Android strips
 * the 2-byte company ID -- that company ID is passed in separately as
 * [companyId] because the checksum covers it too):
 *   [0]    Battery voltage -- raw/10 = volts
 *   [1]    Temperature -- direct °C
 *   [2,3]  Pressure -- 16-bit big-endian raw; kPa = raw16 - 101 (floor 101
 *          before subtracting); bar = kPa / 100.0
 *   [4]    Reserved -- always 0x00 in every frame captured so far
 *   [5]    Checksum -- CRC-8-like (init=0xDF, poly=0x2F), computed over
 *          [companyIdLowByte, companyIdHighByte, byte0, byte1, byte2, byte3]
 *   [6..11] Sensor's own MAC address, echoed verbatim
 *
 * NOTE: there is no separate battery-low bit in this frame. An earlier
 * decoder version misread byte[2] (actually pressure's high byte) as a
 * battery flag -- it looked plausible only because pressure's high byte
 * occasionally flips between 0 and 1. The real, verified battery signal
 * is the voltage byte, thresholded below.
 */
object TpmsDecoder {

    private const val CHECKSUM_INIT = 223
    private const val CHECKSUM_POLY = 47

    // Matches the threshold used by the reference apps' older sensor
    // generation (WheeledCarMode's voltage > 2.5 -> OK check) and is a
    // standard CR-series lithium coin cell end-of-life cutoff.
    private const val LOW_BATTERY_VOLTAGE_THRESHOLD = 2.5f

    enum class Position { FRONT, REAR, UNKNOWN }

    data class TpmsReading(
        val position: Position,
        val mac: String,
        val pressureBar: Float,
        val temperatureC: Int,
        val voltage: Float,
        val batteryOk: Boolean,
        val rawBytes: ByteArray
    )

    /**
     * Determines whether [mac] matches the configured front or rear sensor address.
     */
    fun positionForMac(mac: String, frontMac: String, rearMac: String): Position = when (mac.uppercase()) {
        frontMac.uppercase() -> Position.FRONT
        rearMac.uppercase() -> Position.REAR
        else -> Position.UNKNOWN
    }

    /**
     * Decodes a 12-byte manufacturer data payload.
     * [companyId] is the 2-byte manufacturer ID Android reports via
     * ScanRecord.getManufacturerSpecificData().keyAt(0) -- NOT hardcoded,
     * because different sensors/frames use different values here (verified:
     * front used 0x0000, rear used 0x0800 in the same capture session) and
     * the checksum covers these 2 bytes.
     *
     * Returns null if the payload is too short, doesn't echo the scanning
     * device's own MAC in bytes [6..11] (rejects unrelated nearby BLE
     * devices), or fails the checksum (rejects corrupted frames).
     */
    fun decode(position: Position, mac: String, companyId: Int, data: ByteArray): TpmsReading? {
        if (data.size < 12) return null
        if (!macMatchesPayload(mac, data)) return null

        val unsigned: (Int) -> Int = { idx -> data[idx].toInt() and 0xFF }

        val companyLow = (companyId and 0xFF).toByte()
        val companyHigh = ((companyId shr 8) and 0xFF).toByte()
        val checksumInput = byteArrayOf(companyLow, companyHigh, data[0], data[1], data[2], data[3])
        if (calculateChecksum(checksumInput) != unsigned(5)) return null

        val voltage = unsigned(0) / 10.0f
        val batteryOk = voltage > LOW_BATTERY_VOLTAGE_THRESHOLD

        val temperatureC = unsigned(1)

        val pressureRaw16 = (unsigned(2) shl 8) or unsigned(3)
        val kpa = (if (pressureRaw16 < 101) 101 else pressureRaw16) - 101
        val pressureBar = kpa / 100.0f

        return TpmsReading(
            position = position,
            mac = mac,
            pressureBar = pressureBar,
            temperatureC = temperatureC,
            voltage = voltage,
            batteryOk = batteryOk,
            rawBytes = data.copyOf()
        )
    }

    /**
     * CRC-8-like checksum, ported directly from the reference apps'
     * CYUtils.calculateChecksum() and verified bit-exact against real
     * captured frames.
     */
    private fun calculateChecksum(bytes: ByteArray): Int {
        var i = CHECKSUM_INIT
        for (b in bytes) {
            i = i xor (b.toInt() and 0xFF)
            repeat(8) {
                i = if (i and 128 != 0) (i shl 1) xor CHECKSUM_POLY else i shl 1
            }
        }
        return i and 0xFF
    }

    private fun macMatchesPayload(mac: String, data: ByteArray): Boolean {
        val macBytes = mac.split(":").map { it.toIntOrNull(16) ?: return false }
        if (macBytes.size != 6) return false
        for (idx in 0 until 6) {
            if ((data[6 + idx].toInt() and 0xFF) != macBytes[idx]) return false
        }
        return true
    }
}
