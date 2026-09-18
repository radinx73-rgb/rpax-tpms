package com.rpax.tpms

/**
 * Decoder for DJTPMS BLE 12-byte Manufacturer Specific Data frames.
 *
 * Frame layout (indices 0..11), skalibrowane i zweryfikowane pompką +
 * cyfrowym manometrem na realnych czujnikach (patrz notatki projektu):
 *   [0]    Sequence / Session ID
 *   [1]    Temperatura -- wprost w °C (bez przeliczeń)
 *   [2]    Flaga baterii -- tylko najmłodszy bit (0 = OK, 1 = niska),
 *          reszta bitów nieużywana/zarezerwowana (potwierdzone też
 *          w kodzie referencyjnej apki producenta: `value & 1`)
 *   [3]    Ciśnienie -- 8-bit ADC, zawija się co 256 (patrz
 *          PRESSURE_WRAP_THRESHOLD), bar = 0.01116×raw − 1.209
 *   [4..5] Checksum / Flags
 *   [6..11] Sensor's hardware MAC address (echoed in payload)
 */
object TpmsDecoder {

    // Próg wykrywania "zawinięcia" 8-bitowego ADC ciśnienia: gdy surowy
    // odczyt spadnie poniżej tej wartości, oznacza to, że licznik
    // przekręcił się przez 255 -> 0, więc doliczamy 256, aby zachować
    // ciągłość skali przed przeliczeniem na bar.
    private const val PRESSURE_WRAP_THRESHOLD: Int = 130
    private const val PRESSURE_SCALE: Float = 0.01116f
    private const val PRESSURE_OFFSET: Float = 1.209f

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
     * Returns null if payload is invalid, too short, or doesn't carry the
     * scanning device's own MAC echoed in bytes [6..11] -- that echo is
     * confirmed present in real captured frames, and checking it rejects
     * manufacturer data from some unrelated nearby BLE device that happens
     * to also be >=12 bytes (which would otherwise look like a valid
     * frame and could get bound during pairing to the wrong "sensor").
     */
    fun decode(position: Position, mac: String, data: ByteArray): TpmsReading? {
        if (data.size < 12) return null
        if (!macMatchesPayload(mac, data)) return null

        val unsigned: (Int) -> Int = { idx -> data[idx].toInt() and 0xFF }

        // Temperatura: bajt [1] wprost w °C
        val temperatureC = unsigned(1)

        // Bateria: bajt [2], tylko najmłodszy bit -- flaga gotowa z czujnika
        val batteryOk = (unsigned(2) and 1) == 0

        // Ciśnienie: bajt [3], 8-bit ADC z zawijaniem co 256
        val rawPressure = unsigned(3)
        val effectivePressureRaw = if (rawPressure < PRESSURE_WRAP_THRESHOLD) {
            rawPressure + 256
        } else {
            rawPressure
        }
        val pressureBar = PRESSURE_SCALE * effectivePressureRaw - PRESSURE_OFFSET

        return TpmsReading(
            position = position,
            mac = mac,
            pressureBar = pressureBar,
            temperatureC = temperatureC,
            batteryOk = batteryOk,
            rawBytes = data.copyOf()
        )
    }

    private fun macMatchesPayload(mac: String, data: ByteArray): Boolean {
        val macBytes = mac.split(":").map { it.toIntOrNull(16) ?: return false }
        if (macBytes.size != 6) return false
        for (i in 0 until 6) {
            if ((data[6 + i].toInt() and 0xFF) != macBytes[i]) return false
        }
        return true
    }
}
