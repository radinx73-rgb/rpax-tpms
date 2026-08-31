package com.rpax.tpms

/**
 * Decoder for DJTPMS-style 12-byte Manufacturer Specific Data frames.
 *
 * Frame layout (indices 0..11):
 *   [0] pressure raw (front sensor primary field, rear sensor primary field)
 *   [1] reserved / sequence
 *   [2] battery status (0x01 = OK)
 *   [3] pressure raw (rear sensor secondary field, used with status flag)
 *   [4] reserved
 *   [5] temperature raw, offset -87 -> deg C
 *   [6..11] reserved / sensor id tail
 *
 * Pressure conversion: bar = rawByte / 18.125f
 * Temperature conversion: celsius = rawByte - 87
 */
object TpmsDecoder {

    const val FRONT_MAC = "9C:7F:64:5B:2A:04"
    const val REAR_MAC = "9C:7F:64:5B:2C:63"

    private const val PRESSURE_DIVISOR = 18.125f
    private const val TEMP_OFFSET = 87
    private const val BATTERY_OK: Int = 0x01
    private const val REAR_STATUS_FLAG = 0x53

    enum class Position { FRONT, REAR, UNKNOWN }

    data class TpmsReading(
        val position: Position,
        val mac: String,
        val pressureBar: Float,
        val temperatureC: Int,
        val batteryOk: Boolean,
        val rawBytes: ByteArray
    )

    fun positionForMac(mac: String): Position = when (mac.uppercase()) {
        FRONT_MAC -> Position.FRONT
        REAR_MAC -> Position.REAR
        else -> Position.UNKNOWN
    }

    /**
     * Decode a 12-byte manufacturer data payload for a sensor identified by [mac].
     * Returns null if the payload is too short to be a valid frame.
     */
    fun decode(mac: String, data: ByteArray): TpmsReading? {
        if (data.size < 12) return null

        val position = positionForMac(mac)
        val unsigned: (Int) -> Int = { idx -> data[idx].toInt() and 0xFF }

        val batteryRaw = unsigned(2)
        val batteryOk = batteryRaw == BATTERY_OK

        val tempRaw = unsigned(5)
        val temperatureC = tempRaw - TEMP_OFFSET

        val pressureBar: Float = when (position) {
            Position.FRONT -> {
                val raw0 = unsigned(0)
                raw0 / PRESSURE_DIVISOR
            }
            Position.REAR -> {
                // Rear sensor may report the live value in byte 0 or byte 3.
                // Byte 3 carries a status flag (0x53) that indicates the
                // "confirmed" reading channel, used to disambiguate cases
                // like 2.4 bar where byte 0 alone would be ambiguous.
                val raw0 = unsigned(0)
                val raw3 = unsigned(3)
                val statusFlag = unsigned(1)

                val chosenRaw = if (statusFlag == REAR_STATUS_FLAG || raw3 in 1..255) {
                    if (raw3 > 0) raw3 else raw0
                } else {
                    raw0
                }
                chosenRaw / PRESSURE_DIVISOR
            }
            Position.UNKNOWN -> unsigned(0) / PRESSURE_DIVISOR
        }

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
