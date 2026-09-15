package com.rpax.tpms

/**
 * Decoder for this DJTPMS-style 12-byte Manufacturer Specific Data frame.
 *
 * Frame layout (indices 0..11), reverse-engineered from real calibration
 * data (multiple known pressures and temperatures captured against raw
 * frames from both sensors):
 *   [0] constant/session/model byte -- not used
 *   [1] temperature, directly in whole degrees Celsius (no offset)
 *   [2] status flag -- previously assumed "battery OK" when == 0x01;
 *       real-world testing left this only partially confirmed
 *   [3] pressure ADC reading, 8-bit, wraps around every 256 counts
 *       (see decode() for the unwrap + linear formula)
 *   [4] always observed as 0x00 -- reserved, not used
 *   [5] does not correlate with any known reference value -- not used
 *   [6..11] the sensor's own MAC address, echoed back in the payload
 *
 * Identical formula applies to both front and rear sensors -- there is no
 * position-specific special-casing needed.
 *
 * Sensor MAC addresses are NOT hardcoded here -- every physical BLE sensor
 * has its own unique factory MAC, so which two addresses count as "front"
 * and "rear" is a per-installation setting (see TpmsSettings.frontMac /
 * rearMac), configurable from the app's settings screen. This lets anyone
 * pair a different physical pair of DJTPMS-protocol sensors without needing
 * a code change or a new build.
 */
object TpmsDecoder {

    // Defaults matching the sensors this app was originally built for.
    // Only used the very first time the app runs, before the user has set
    // their own sensor MACs in settings.
    const val DEFAULT_FRONT_MAC = "9C:7F:64:5B:2A:04"
    const val DEFAULT_REAR_MAC = "9C:7F:64:5B:2C:63"

    private const val PRESSURE_SLOPE = 0.01116f
    private const val PRESSURE_INTERCEPT = -1.209f
    private const val PRESSURE_WRAP_THRESHOLD = 130
    private const val BATTERY_OK: Int = 0x01

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
     * Determine whether [mac] matches the configured front or rear sensor
     * address. [frontMac] / [rearMac] come from TpmsSettings (user-editable).
     */
    fun positionForMac(mac: String, frontMac: String, rearMac: String): Position = when (mac.uppercase()) {
        frontMac.uppercase() -> Position.FRONT
        rearMac.uppercase() -> Position.REAR
        else -> Position.UNKNOWN
    }

    /**
     * Decode a 12-byte manufacturer data payload for a sensor already
     * identified as [position] (via [positionForMac]). Returns null if the
     * payload is too short to be a valid frame.
     *
     * Byte layout, reverse-engineered from real calibration data (multiple
     * known pressures/temperatures captured against raw frames -- see
     * project notes for the underlying measurements):
     *   [0] appears to be a constant/session/model byte -- not used
     *   [1] temperature, directly in whole degrees Celsius (no offset)
     *   [2] status flag, meaning not fully confirmed (previously assumed
     *       "battery OK" when == 0x01; kept as a best-effort indicator)
     *   [3] pressure ADC reading, 8-bit, WRAPS AROUND every 256 counts.
     *       Formula: bar = PRESSURE_SLOPE * raw + PRESSURE_INTERCEPT, where
     *       raw has 256 added if the raw byte is below PRESSURE_WRAP_THRESHOLD
     *       (empirically, real raw values for pressures above ~1.6 bar wrap
     *       below that threshold). Identical formula for front and rear --
     *       there is no special-casing needed between sensor positions.
     *   [4] always observed as 0x00 -- reserved, not used
     *   [5] varies without correlating to any known reference value in
     *       calibration data -- not used (previously wrongly assumed to be
     *       temperature)
     *   [6..11] the sensor's own MAC address, echoed back in the payload
     */
    fun decode(position: Position, mac: String, data: ByteArray): TpmsReading? {
        if (data.size < 12) return null

        val unsigned: (Int) -> Int = { idx -> data[idx].toInt() and 0xFF }

        val batteryRaw = unsigned(2)
        val batteryOk = batteryRaw == BATTERY_OK

        val temperatureC = unsigned(1)

        val rawPressureByte = unsigned(3)
        val effectiveRaw = if (rawPressureByte < PRESSURE_WRAP_THRESHOLD) {
            rawPressureByte + 256
        } else {
            rawPressureByte
        }
        val pressureBar = PRESSURE_SLOPE * effectiveRaw + PRESSURE_INTERCEPT

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
