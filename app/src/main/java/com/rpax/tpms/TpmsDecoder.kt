package com.rpax.tpms

/**
 * Decoder for DJTPMS BLE 12-byte Manufacturer Specific Data frames.
 *
 * Frame layout (indices 0..11):
 *   [0] Sequence / Session ID
 *   [1] Status / Battery state flag
 *   [2..3] Pressure 16-bit Big-Endian (MSB at index 2, LSB at index 3)
 *   [4] Raw temperature byte
 *   [5] Checksum / Flags
 *   [6..11] Sensor's hardware MAC address (echoed in payload)
 */
object TpmsDecoder {

    const val DEFAULT_FRONT_MAC = "9C:7F:64:5B:2A:04"
    const val DEFAULT_REAR_MAC = "9C:7F:64:5B:2C:63"

    private const val BATTERY_THRESHOLD: Int = 0x10

    enum class Position { FRONT, REAR, UNKNOWN }

    data class TpmsReading(
        val position: Position,
        val mac: String,
        val pressureBar: Float,
        val temperatureC: Int,
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
     * Returns null if payload is invalid or too short.
     */
    fun decode(position: Position, mac: String, data: ByteArray): TpmsReading? {
        if (data.size < 12) return null

        val unsigned: (Int) -> Int = { idx -> data[idx].toInt() and 0xFF }

        // Status baterii
        val batteryOk = unsigned(1) > BATTERY_THRESHOLD

        // Ciśnienie: 16-bit Big-Endian z bajtów [2] i [3]
        val rawPressure16 = (unsigned(2) shl 8) or unsigned(3)
        val pressureBar = rawPressure16 * 0.005f

        // Temperatura: Bajt [4] z przeliczeniem offsetu
        val rawTemp = unsigned(4)
        val temperatureC = if (rawTemp > 100) rawTemp - 225 else rawTemp - 50

        return TpmsReading(
            position = position,
            mac = mac,
            pressureBar = pressureBar,
            temperatureC = temperatureC,
            batteryOk = batteryOk,
            rawBytes = data.copyOf()
        )
    }
}
